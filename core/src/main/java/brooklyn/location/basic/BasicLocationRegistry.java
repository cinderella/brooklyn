package brooklyn.location.basic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import java.util.Set;

import brooklyn.config.StringConfigMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.BrooklynProperties;
import brooklyn.config.ConfigMap;
import brooklyn.config.ConfigPredicates;
import brooklyn.config.ConfigUtils;
import brooklyn.location.Location;
import brooklyn.location.LocationDefinition;
import brooklyn.location.LocationResolver;
import brooklyn.management.ManagementContext;
import brooklyn.util.MutableMap;
import brooklyn.util.internal.LanguageUtils;
import brooklyn.util.text.WildcardGlobs;
import brooklyn.util.text.WildcardGlobs.PhraseTreatment;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

@SuppressWarnings({"rawtypes","unchecked"})
public class BasicLocationRegistry implements brooklyn.location.LocationRegistry {

    public static final Logger log = LoggerFactory.getLogger(BasicLocationRegistry.class);
    
    /** @deprecated only for compatibility with legacy {@link LocationRegistry} */
    private final Map properties;

    private final ManagementContext mgmt;
    /** map of defined locations by their ID */
    private final Map<String,LocationDefinition> definedLocations = new LinkedHashMap<String, LocationDefinition>();

    protected final Map<String,LocationResolver> resolvers = new LinkedHashMap<String, LocationResolver>();

    public BasicLocationRegistry(ManagementContext mgmt) {
        this.mgmt = mgmt;
        this.properties = null;
        findServices();
        findDefinedLocations();
    }

    @Deprecated
    protected BasicLocationRegistry(){
        this(BrooklynProperties.Factory.newDefault());
        if (log.isDebugEnabled())
            log.debug("Using the LocationRegistry no arg constructor will rely on the properties defined in ~/.brooklyn/brooklyn.properties, " +
                    "potentially bypassing explicitly loaded properties", new Throwable("source of no-arg LocationRegistry constructor"));
    }

    @Deprecated
    protected BasicLocationRegistry(Map properties) {
        this.properties = properties;
        this.mgmt = null;
        findServices();
    }
    
    protected void findServices() {
        ServiceLoader<LocationResolver> loader = ServiceLoader.load(LocationResolver.class);
        for (LocationResolver r: loader)
            resolvers.put(r.getPrefix(), r);
        if (log.isDebugEnabled()) log.debug("Location resolvers are: "+resolvers);
        if (resolvers.isEmpty()) log.warn("No location resolvers detected: is src/main/resources correclty included?");
    }

    public Map<String,LocationDefinition> getDefinedLocations() {
        synchronized (definedLocations) {
            return ImmutableMap.<String,LocationDefinition>copyOf(definedLocations);
        }
    }
    
    public LocationDefinition getDefinedLocation(String id) {
        return definedLocations.get(id);
    }

    public LocationDefinition getDefinedLocationByName(String name) {
        synchronized (definedLocations) {
            for (LocationDefinition l: definedLocations.values()) {
                if (l.getName().equals(name)) return l;
            }
            return null;
        }
    }

    @Override
    public void updateDefinedLocation(LocationDefinition l) {
        synchronized (definedLocations) { 
            definedLocations.put(l.getId(), l); 
        }
    }

    @Override
    public void removeDefinedLocation(String id) {
        synchronized (definedLocations) { 
            definedLocations.remove(id); 
        }
    }
    
    protected void findDefinedLocations() {
        synchronized (definedLocations) {
            // first read all properties starting  brooklyn.location.named.xxx
            // (would be nice to move to a better way, then deprecate this approach, but first
            // we need ability/format for persisting named locations, and better support for adding+saving via REST/GUI)
            int count = 0;
            String NAMED_LOCATION_PREFIX = "brooklyn.location.named.";
            StringConfigMap configMap = (mgmt == null) ? BrooklynProperties.Factory.newDefault() : mgmt.getConfig();
            ConfigMap namedLocationProps = configMap.submap(ConfigPredicates.startingWith(NAMED_LOCATION_PREFIX));
            for (String k: namedLocationProps.asMapWithStringKeys().keySet()) {
                String name = k.substring(NAMED_LOCATION_PREFIX.length());
                // If has a dot, then is a sub-property of a named location (e.g. brooklyn.location.named.prod1.user=bob)
                if (!name.contains(".")) {
                    // this is a new named location
                    String spec = (String) namedLocationProps.asMapWithStringKeys().get(k);
                    // make up an ID
                    String id = LanguageUtils.newUid();
                    Map<String, Object> config = ConfigUtils.filterForPrefixAndStrip(namedLocationProps.asMapWithStringKeys(), k+".");
                    definedLocations.put(id, new BasicLocationDefinition(id, name, spec, config));
                    count++;
                }
            }
            if (log.isDebugEnabled())
                log.debug("Found "+count+" defined locations from properties (*.named.* syntax): "+definedLocations.values());
            if (getDefinedLocationByName("localhost")==null && !BasicOsDetails.Factory.newLocalhostInstance().isWindows()) {
                log.debug("Adding a defined location for localhost");
                // add 'localhost' *first*
                ImmutableMap<String, LocationDefinition> oldDefined = ImmutableMap.copyOf(definedLocations);
                definedLocations.clear();
                String id = LanguageUtils.newUid();
                definedLocations.put(id, localhost(id));
                definedLocations.putAll(oldDefined);
            }
        }
    }
    
    // TODO save / serialize
    
    @VisibleForTesting
    void disablePersistence() {
        // persistence isn't enabled yet anyway (have to manually save things,
        // defining the format and file etc)
    }

    BasicLocationDefinition localhost(String id) {
        return new BasicLocationDefinition(id, "localhost", "localhost", null);
    }
    
    /** to catch circular references */
    protected ThreadLocal<Set<String>> specsSeen = new ThreadLocal<Set<String>>();
    
    public boolean canResolve(String spec) {
        return getSpecResolver(spec) != null;
    }

    public final Location resolve(String spec) {
        return resolve(spec, new MutableMap());
    }
    public Location resolve(String spec, Map locationFlags) {
        try {
            Set<String> seenSoFar = specsSeen.get();
            if (seenSoFar==null) {
                seenSoFar = new LinkedHashSet<String>();
                specsSeen.set(seenSoFar);
            }
            if (seenSoFar.contains(spec))
                throw new IllegalStateException("Circular reference in definition of location '"+spec+"' ("+seenSoFar+")");
            seenSoFar.add(spec);
            
            LocationResolver resolver = getSpecResolver(spec);

            if (resolver instanceof RegistryLocationResolver) {

                if (getDefinedLocations().isEmpty()) {
                   findDefinedLocations();
                }

                return ((RegistryLocationResolver)resolver).newLocationFromString(locationFlags, spec, this);
            } else if (resolver != null) {
                if (!locationFlags.isEmpty())
                    log.warn("Ignoring location flags "+locationFlags+" when instantiating "+spec);
                return resolver.newLocationFromString(properties, spec);
            }

            throw new NoSuchElementException("No resolver found for '"+spec+"'");
        } finally {
            specsSeen.remove();
        }
    }

    protected LocationResolver getSpecResolver(String spec) {
        int colon = spec.indexOf(':');
        String prefix = colon>=0 ? spec.substring(0, colon) : spec;
        LocationResolver resolver = resolvers.get(prefix);
       
        if (resolver == null)
            resolver = getSpecDefaultResolver(spec);
        
        return resolver;
    }
    
    protected LocationResolver getSpecDefaultResolver(String spec) {
        return getSpecFirstResolver(spec, "id", "named", "jclouds");
    }
    protected LocationResolver getSpecFirstResolver(String spec, String ...resolversToCheck) {
        for (String resolverId: resolversToCheck) {
            LocationResolver resolver = resolvers.get(resolverId);
            if (resolver!=null && (resolver instanceof RegistryLocationResolver) && ((RegistryLocationResolver)resolver).accepts(spec, this))
                return resolver;
        }
        return null;
    }

    /** providers default impl for RegistryLocationResolver.accepts */
    public static boolean isResolverPrefixForSpec(LocationResolver resolver, String spec, boolean argumentRequired) {
        if (spec==null) return false;
        if (spec.startsWith(resolver.getPrefix()+":")) return true;
        if (!argumentRequired && spec.equals(resolver.getPrefix())) return true;
        return false;
    }

    /** @deprecated use resolve */
    public List<Location> getLocationsById(Iterable<?> specs) {
        return resolve(specs);
    }
    
    /**
     * Expects a collection of strings being the spec for locations, returns a list of locations.
     * Also allows single elements which are comma-separated lists of locations.
     * <p>
     * For legacy compatibility this also accepts nested lists, but that is deprecated
     * (and triggers a warning).
     */
    public List<Location> resolve(Iterable<?> spec) {
        List<Location> result = new ArrayList<Location>();
        for (Object id : spec) {
            if (id instanceof String) {
                // if it as comma-separated list -- TODO with no comma in the brackets
                List<String> l = expandCommaSeparateLocationSpecList((String)id);
                if (l.size()>1) id = l;
            } else if (id instanceof Iterable) {
                log.warn("LocationRegistry got list of list of location strings, "+spec+"; flattening");
            }
            if (id instanceof String) {
                result.add(resolve((String) id));
            } else if (id instanceof Iterable) {
                result.addAll(getLocationsById((Iterable<?>) id));
            } else if (id instanceof Location) {
                result.add((Location) id);
            } else {
                throw new IllegalArgumentException("Cannot resolve '"+id+"' to a location; unsupported type "+
                        (id == null ? "null" : id.getClass().getName())); 
            }
        }
        return result;
    }
    
    @Override
    public Location resolve(LocationDefinition ld) {
        return resolveLocationDefinition(ld, Collections.emptyMap(), null);
    }
    
    public Location resolveLocationDefinition(LocationDefinition ld, Map locationFlags, String optionalName) {
        MutableMap newLocationFlags = new MutableMap().add(locationFlags).add(ld.getConfig());
        if (optionalName==null && ld.getName()!=null) optionalName = ld.getName();
        if (optionalName!=null) newLocationFlags.add("name", optionalName);
        try {
            return resolve(ld.getSpec(), newLocationFlags);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot instantiate named location '"+optionalName+"' pointing at "+ld.getSpec()+": "+e, e);
        }
    }

    private List<String> expandCommaSeparateLocationSpecList(String specListCommaSeparated) {
        return WildcardGlobs.getGlobsAfterBraceExpansion("{"+specListCommaSeparated+"}", false, PhraseTreatment.INTERIOR_NOT_EXPANDABLE, PhraseTreatment.INTERIOR_NOT_EXPANDABLE);
        // don't do this, it tries to expand commas inside parentheses which is not good!
//        QuotedStringTokenizer.builder().addDelimiterChars(",").buildList((String)id);
    }
    
    public Map getProperties() {
        if (mgmt!=null) return mgmt.getConfig().asMapWithStringKeys();
        return properties;
    }

    @VisibleForTesting
    public static void setupLocationRegistryForTesting(ManagementContext mgmt) {
        // ensure localhost is added (even on windows)
        LocationDefinition l = mgmt.getLocationRegistry().getDefinedLocationByName("localhost");
        if (l==null) mgmt.getLocationRegistry().updateDefinedLocation(
                ((BasicLocationRegistry)mgmt.getLocationRegistry()).localhost(LanguageUtils.newUid()));
        
        ((BasicLocationRegistry)mgmt.getLocationRegistry()).disablePersistence();
    }

}
