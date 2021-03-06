package brooklyn.management.internal;

import static brooklyn.util.JavaGroovyEquivalents.elvis;
import static brooklyn.util.JavaGroovyEquivalents.groovyTruth;
import static brooklyn.util.JavaGroovyEquivalents.join;
import static brooklyn.util.JavaGroovyEquivalents.mapOf;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.event.Sensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.management.ExecutionManager;
import brooklyn.management.SubscriptionHandle;
import brooklyn.management.SubscriptionManager;
import brooklyn.util.internal.LanguageUtils;
import brooklyn.util.task.BasicExecutionManager;
import brooklyn.util.task.SingleThreadedScheduler;
import brooklyn.util.text.Identifiers;

import com.google.common.base.Predicate;

/**
 * A {@link SubscriptionManager} that stores subscription details locally.
 */
public class LocalSubscriptionManager extends AbstractSubscriptionManager {
    
    private static final Logger LOG = LoggerFactory.getLogger(LocalSubscriptionManager.class);

    protected final ExecutionManager em;
    
    private final String tostring = "SubscriptionContext("+Identifiers.getBase64IdFromValue(System.identityHashCode(this), 5)+")";

    private final AtomicLong totalEventsPublishedCount = new AtomicLong();
    private final AtomicLong totalEventsDeliveredCount = new AtomicLong();
    
    @SuppressWarnings("rawtypes")
    protected final ConcurrentMap<String, Subscription> allSubscriptions = new ConcurrentHashMap<String, Subscription>();
    @SuppressWarnings("rawtypes")
    protected final ConcurrentMap<Object, Set<Subscription>> subscriptionsBySubscriber = new ConcurrentHashMap<Object, Set<Subscription>>();
    @SuppressWarnings("rawtypes")
    protected final ConcurrentMap<Object, Set<Subscription>> subscriptionsByToken = new ConcurrentHashMap<Object, Set<Subscription>>();
    
    public LocalSubscriptionManager(ExecutionManager m) {
        this.em = m;
    }
        
    public long getNumSubscriptions() {
        return allSubscriptions.size();
    }

    public long getTotalEventsPublished() {
        return totalEventsPublishedCount.get();
    }
    
    public long getTotalEventsDelivered() {
        return totalEventsDeliveredCount.get();
    }
    
    @SuppressWarnings("unchecked")
    protected synchronized <T> SubscriptionHandle subscribe(Map<String, Object> flags, Subscription<T> s) {
        Entity producer = s.producer;
        Sensor<T> sensor= s.sensor;
        s.subscriber = getSubscriber(flags, s);
        if (flags.containsKey("subscriberExecutionManagerTag")) {
            s.subscriberExecutionManagerTag = flags.remove("subscriberExecutionManagerTag");
            s.subscriberExecutionManagerTagSupplied = true;
        } else {
            s.subscriberExecutionManagerTag = 
                s.subscriber instanceof Entity ? "subscription-delivery-entity-"+((Entity)s.subscriber).getId()+"["+s.subscriber+"]" : 
                s.subscriber instanceof String ? "subscription-delivery-string["+s.subscriber+"]" : 
                "subscription-delivery-object["+s.subscriber+"]";
            s.subscriberExecutionManagerTagSupplied = false;
        }
        s.eventFilter = (Predicate<SensorEvent<T>>) flags.remove("eventFilter");
        s.flags = flags;
        
        if (LOG.isDebugEnabled()) LOG.debug("Creating subscription {} for {} on {} {} in {}", new Object[] {s.id, s.subscriber, producer, sensor, this});
        allSubscriptions.put(s.id, s);
        LanguageUtils.addToMapOfSets(subscriptionsByToken, makeEntitySensorToken(s.producer, s.sensor), s);
        if (s.subscriber!=null) {
            LanguageUtils.addToMapOfSets(subscriptionsBySubscriber, s.subscriber, s);
        }
        if (!s.subscriberExecutionManagerTagSupplied && s.subscriberExecutionManagerTag!=null) {
            ((BasicExecutionManager) em).setTaskSchedulerForTag(s.subscriberExecutionManagerTag, SingleThreadedScheduler.class);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    public Set<SubscriptionHandle> getSubscriptionsForSubscriber(Object subscriber) {
        return (Set<SubscriptionHandle>) ((Set<?>) elvis(subscriptionsBySubscriber.get(subscriber), Collections.emptySet()));
    }

    public synchronized Set<SubscriptionHandle> getSubscriptionsForEntitySensor(Entity source, Sensor<?> sensor) {
        Set<SubscriptionHandle> subscriptions = new LinkedHashSet<SubscriptionHandle>();
        subscriptions.addAll(elvis(subscriptionsByToken.get(makeEntitySensorToken(source, sensor)), Collections.emptySet()));
        subscriptions.addAll(elvis(subscriptionsByToken.get(makeEntitySensorToken(null, sensor)), Collections.emptySet()));
        subscriptions.addAll(elvis(subscriptionsByToken.get(makeEntitySensorToken(source, null)), Collections.emptySet()));
        subscriptions.addAll(elvis(subscriptionsByToken.get(makeEntitySensorToken(null, null)), Collections.emptySet()));
        return subscriptions;
    }

    /**
     * Unsubscribe the given subscription id.
     *
     * @see #subscribe(Map, Entity, Sensor, SensorEventListener)
     */
    @SuppressWarnings("rawtypes")
    public synchronized boolean unsubscribe(SubscriptionHandle sh) {
        if (!(sh instanceof Subscription)) throw new IllegalArgumentException("Only subscription handles of type Subscription supported: sh="+sh+"; type="+(sh != null ? sh.getClass().getCanonicalName() : null));
        Subscription s = (Subscription) sh;
        boolean result = allSubscriptions.remove(s.id) != null;
        boolean b2 = LanguageUtils.removeFromMapOfCollections(subscriptionsByToken, makeEntitySensorToken(s.producer, s.sensor), s);
        assert result==b2;
        if (s.subscriber!=null) {
            boolean b3 = LanguageUtils.removeFromMapOfCollections(subscriptionsBySubscriber, s.subscriber, s);
            assert b3 == b2;
        }
        
        // TODO Requires code review: why did we previously do exactly same check twice in a row (with no synchronization in between)? 
        if ((subscriptionsBySubscriber.size() == 0 || !groovyTruth(subscriptionsBySubscriber.get(s.subscriber))) && !s.subscriberExecutionManagerTagSupplied && s.subscriberExecutionManagerTag!=null) {
            //if subscriber has gone away forget about his task; but check in synch block to ensure setTaskPreprocessor call above will win in any race
            if ((subscriptionsBySubscriber.size() == 0 || !groovyTruth(subscriptionsBySubscriber.get(s.subscriber))))
                ((BasicExecutionManager)em).clearTaskPreprocessorForTag(s.subscriberExecutionManagerTag);
        }

		//FIXME ALEX - this seems wrong
        ((BasicExecutionManager) em).setTaskSchedulerForTag(s.subscriberExecutionManagerTag, SingleThreadedScheduler.class);
        return result;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> void publish(final SensorEvent<T> event) {
        // REVIEW 1459 - execution
        
        // delivery in parallel/background, using execution manager
        
        // subscriptions, should define SingleThreadedScheduler for any subscriber ID tag
        // in order to ensure callbacks are invoked in the order they are submitted
        // (recommend exactly one per subscription to prevent deadlock)
        // this is done with:
        // em.setTaskSchedulerForTag(subscriberId, SingleThreadedScheduler.class);
        
        //note, generating the notifications must be done in the calling thread to preserve order
        //e.g. emit(A); emit(B); should cause onEvent(A); onEvent(B) in that order
        if (LOG.isTraceEnabled()) LOG.trace("{} got event {}", this, event);
        totalEventsPublishedCount.incrementAndGet();
        
        Set<Subscription> subs = (Set<Subscription>) ((Set<?>) getSubscriptionsForEntitySensor(event.getSource(), event.getSensor()));
        if (groovyTruth(subs)) {
            if (LOG.isTraceEnabled()) LOG.trace("sending {}, {} to {}", new Object[] {event.getSensor().getName(), event, join(subs, ",")});
            for (Subscription s : subs) {
                if (s.eventFilter!=null && !s.eventFilter.apply(event))
                    continue;
                final Subscription sAtClosureCreation = s;
                em.submit(mapOf("tag", s.subscriberExecutionManagerTag), new Runnable() {
                    public void run() {
                        sAtClosureCreation.listener.onEvent(event);
                    }});
                totalEventsDeliveredCount.incrementAndGet();
            }
        }
    }
    
    @Override
    public String toString() {
        return tostring;
    }
}
