package brooklyn.util.internal

import static brooklyn.test.TestUtils.*
import static org.testng.Assert.*

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

import brooklyn.entity.SimpleEntity
import brooklyn.entity.basic.AbstractEntity
import brooklyn.event.adapter.SensorRegistry
import brooklyn.event.adapter.legacy.ValueProvider
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.management.internal.LocalManagementContext
import brooklyn.test.TestUtils

/**
 * Test the operation of the {@link SensorRegistry} class.
 */
public class SensorRegistryTest {
    private static final Logger log = LoggerFactory.getLogger(SensorRegistryTest.class)

    AbstractEntity entity;
    
    @BeforeMethod(alwaysRun=true)
    public void setup() {
        entity = new SimpleEntity()
        new LocalManagementContext().manage(entity);
    }
    
    @Test
    public void sensorUpdatedPeriodically() {
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:50])
        
        final AtomicInteger desiredVal = new AtomicInteger(1)
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { return desiredVal.get() } as ValueProvider)

        executeUntilSucceeds {
            assertEquals(entity.getAttribute(FOO), 1)
        }
        desiredVal.set(2)
        executeUntilSucceeds {
            assertEquals(entity.getAttribute(FOO), 2)
        }
    }
    
    @Test
    public void sensorUpdateDefaultPeriodIsUsed() {
        final int PERIOD = 250
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:PERIOD, connectDelay:0])
        
        List<Long> callTimes = [] as CopyOnWriteArrayList
        
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { callTimes.add(System.currentTimeMillis()); return 1 } as ValueProvider)
        
        Thread.sleep(500)
        assertApproxPeriod(callTimes, PERIOD, 500)
    }

    // takes 500ms, so marked as integration
    @Test(groups="Integration")
    public void sensorUpdatePeriodOverrideIsUsed() {
        final int PERIOD = 250
        // Create an entity and configure it with the above JMX service
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:1000, connectDelay:0])
        
        List<Long> callTimes = [] as CopyOnWriteArrayList
        
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { callTimes.add(System.currentTimeMillis()); return 1 } as ValueProvider, PERIOD)
        
        Thread.sleep(500)
        assertApproxPeriod(callTimes, PERIOD, 500)
    }

    @Test(groups="Integration")
    public void testRemoveSensorStopsItBeingUpdatedManyTimes() {
        for (int i=0; i<100; i++) {
            log.info("running testRemoveSensorStopsItBeingUpdated iteration $i");
            try {
                setup();
                testRemoveSensorStopsItBeingUpdated();
            } catch (Throwable t) {
                log.info("failed testRemoveSensorStopsItBeingUpdated, iteration $i: $t");
                throw t;
            }
        }
    }
    
    @Test(groups="Integration")
    public void testRemoveSensorStopsItBeingUpdated() {
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:50])
        
        final AtomicInteger desiredVal = new AtomicInteger(1)
        
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { return desiredVal.get() } as ValueProvider)

        TimeExtras.init();
        TestUtils.executeUntilSucceeds(period:10*TimeUnit.MILLISECONDS, timeout:1*TimeUnit.SECONDS, { entity.getAttribute(FOO)!=null }); 
        assertEquals(entity.getAttribute(FOO), 1)
        
        sensorRegistry.removeSensor(FOO)
        
        // The poller could already be calling the value provider, so can't simply assert never called again.
        // And want to ensure that it is never called again (after any currently executing call), so need to wait.
        // TODO Nicer way than a sleep?  (see comment in TestUtils about need for blockUntilTrue)
        
        int nn = 1;
        TestUtils.executeUntilSucceeds(period:10*TimeUnit.MILLISECONDS, timeout:1*TimeUnit.SECONDS, 
            {
                desiredVal.set(++nn);
                TestUtils.assertSucceedsContinually(period:10*TimeUnit.MILLISECONDS, 
                    timeout:1000*TimeUnit.MILLISECONDS, {
                        entity.getAttribute(FOO)!=nn
                    });
            }
        );
    
        desiredVal.set(-1)
        Thread.sleep(100)
        assertNotEquals(entity.getAttribute(FOO), -1)
        
        sensorRegistry.updateAll()
        assertNotEquals(entity.getAttribute(FOO), -1)
        
        try {
            sensorRegistry.update(FOO)
            fail()
        } catch (IllegalStateException e) {
            // success
        }
    }

    @Test(groups="Integration")
    public void testClosePollerStopsItBeingUpdated() {
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:50])
        
        final AtomicInteger desiredVal = new AtomicInteger(1)
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { return desiredVal.get() } as ValueProvider)

        Thread.sleep(100)
        assertEquals(entity.getAttribute(FOO), 1)
        
        sensorRegistry.close()
        
        // The poller could already be calling the value provider, so can't simply assert never called again.
        // And want to ensure that it is never called again (after any currently executing call), so need to wait.
        // TODO Nicer way than a sleep?
        
        Thread.sleep(100)
        desiredVal.set(2)
        Thread.sleep(100)
        assertEquals(entity.getAttribute(FOO), 1)
    }

    private void assertApproxPeriod(List<Long> actual, int expectedInterval, long expectedDuration) {
        final long ACCEPTABLE_VARIANCE = 200
        long minNextExpected = actual.get(0);
        actual.each {
            assertTrue it >= minNextExpected && it <= (minNextExpected+ACCEPTABLE_VARIANCE), 
                    "expected=$minNextExpected, actual=$it, interval=$expectedInterval, series=$actual, duration=$expectedDuration"
            minNextExpected += expectedInterval
        }
        int expectedSize = expectedDuration/expectedInterval
        assertTrue Math.abs(actual.size()-expectedSize) <= 1, "actualSize=${actual.size()}, series=$actual, duration=$expectedDuration, interval=$expectedInterval"
    }
    
}
