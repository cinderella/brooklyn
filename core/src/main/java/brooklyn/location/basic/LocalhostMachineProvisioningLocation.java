package brooklyn.location.basic;

import static brooklyn.util.GroovyJavaMethods.elvis;
import static brooklyn.util.GroovyJavaMethods.truth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.BrooklynServiceAttributes;
import brooklyn.location.AddressableLocation;
import brooklyn.location.OsDetails;
import brooklyn.location.PortRange;
import brooklyn.location.geo.HostGeoInfo;
import brooklyn.util.MutableMap;
import brooklyn.util.NetworkUtils;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.flags.TypeCoercions;
import brooklyn.util.mutex.MutexSupport;
import brooklyn.util.mutex.WithMutexes;

import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * An implementation of {@link brooklyn.location.MachineProvisioningLocation} that can provision a {@link SshMachineLocation} for the
 * local host.
 *
 * By default you can only obtain a single SshMachineLocation for the localhost. Optionally, you can "overload"
 * and choose to allow localhost to be provisioned multiple times, which may be useful in some testing scenarios.
 */
public class LocalhostMachineProvisioningLocation extends FixedListMachineProvisioningLocation<SshMachineLocation> implements AddressableLocation {

    public static final Logger LOG = LoggerFactory.getLogger(LocalhostMachineProvisioningLocation.class);
                
    @SetFromFlag("count")
    int initialCount;

    @SetFromFlag
    Boolean canProvisionMore;
    
    @SetFromFlag
    InetAddress address;

    private static Set<Integer> portsInUse = Sets.newLinkedHashSet();

    private static HostGeoInfo cachedHostGeoInfo;
        
    /**
     * Construct a new instance.
     *
     * The constructor recognises the following properties:
     * <ul>
     * <li>count - number of localhost machines to make available
     * </ul>
     *
     * @param properties the properties of the new instance.
     */
    public LocalhostMachineProvisioningLocation() {
        this(Maps.newLinkedHashMap());
    }
    public LocalhostMachineProvisioningLocation(Map properties) {
        super(properties);
    }
    public LocalhostMachineProvisioningLocation(String name) {
        this(name, 0);
    }
    public LocalhostMachineProvisioningLocation(String name, int count) {
        this(MutableMap.of("name", name, "count", count));
    }
    
    protected void configure() {
        configure(Maps.newLinkedHashMap());
    }    
    protected void configure(Map flags) {
        super.configure(flags);
        
        if (!truth(name)) { name = "localhost"; }
        if (!truth(address)) address = getLocalhostInetAddress();
        // TODO should try to confirm this machine is accessible on the given address ... but there's no 
        // immediate convenience in java so early-trapping of that particular error is deferred
        
        if (canProvisionMore==null) {
            if (initialCount>0) canProvisionMore = false;
            else canProvisionMore = true;
        }
        if (getHostGeoInfo()==null) {
            if (cachedHostGeoInfo==null)
                cachedHostGeoInfo = HostGeoInfo.fromLocation(this);
            setHostGeoInfo(cachedHostGeoInfo);
        }
        if (initialCount > getMachines().size()) {
            provisionMore(initialCount - getMachines().size());
        }
    }
    
    public static InetAddress getLocalhostInetAddress() {
        return TypeCoercions.coerce(elvis(BrooklynServiceAttributes.LOCALHOST_IP_ADDRESS.getValue(), lookupLocalHost()), InetAddress.class);
    }

    public InetAddress getAddress() { return address; }
    
    public boolean canProvisionMore() { return canProvisionMore; }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void provisionMore(int size) {
        for (int i=0; i<size; i++) {
            Map flags = MutableMap.of(
                    "parentLocation", this, 
                    "address", elvis(address, lookupLocalHost()));
            for (String k: SshMachineLocation.SSH_PROPS) {
                Object v = findLocationProperty(k);
                if (v!=null) flags.put(k, v);
            }
            SshMachineLocation child = new LocalhostMachine(flags);
            addChildLocation(child);
       }
    }

    private static InetAddress lookupLocalHost() {
        try {
            return InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            throw Throwables.propagate(e);
        }
    }
    
    public static synchronized boolean obtainSpecificPort(InetAddress localAddress, int portNumber) {
        if (portsInUse.contains(portNumber)) {
            return false;
        } else {
            //see if it is available?
            if (!checkPortAvailable(localAddress, portNumber)) {
                return false;
            }
            portsInUse.add(portNumber);
            return true;
        }
    }
    /** checks the actual availability of the port on localhost, ie by binding to it; cf {@link NetworkUtils#isPortAvailable(int)} */
    public static boolean checkPortAvailable(InetAddress localAddress, int portNumber) {
        if (portNumber<1024) {
            if (LOG.isDebugEnabled()) LOG.debug("Skipping system availability check for privileged localhost port "+portNumber);
            return true;
        }
        return NetworkUtils.isPortAvailable(portNumber);
    }
    public static int obtainPort(PortRange range) {
        return obtainPort(getLocalhostInetAddress(), range);
    }
    public static int obtainPort(InetAddress localAddress, PortRange range) {
        for (int p: range)
            if (obtainSpecificPort(localAddress, p)) return p;
        if (LOG.isDebugEnabled()) LOG.debug("unable to find port in {} on {}; returning -1", range, localAddress);
        return -1;
    }

    public static synchronized void releasePort(InetAddress localAddress, int portNumber) {
        portsInUse.remove((Object) portNumber);
    }

    public void release(SshMachineLocation machine) {
        LocalhostMachine localMachine = (LocalhostMachine) machine;
        Set<Integer> portsObtained = Sets.newLinkedHashSet();
        synchronized (localMachine.portsObtained) {
            portsObtained.addAll(localMachine.portsObtained);
        }
        
        super.release(machine);
        
        for (int p: portsObtained)
            releasePort(null, p);
    }
    
    public static class LocalhostMachine extends SshMachineLocation {
        private static final WithMutexes mutexSupport = new MutexSupport();
        
        private final Set<Integer> portsObtained = Sets.newLinkedHashSet();

        public LocalhostMachine() {
            this(MutableMap.of("mutexSupport", mutexSupport));
        }
        public LocalhostMachine(Map properties) {
            super(MutableMap.builder().putAll(properties).put("mutexSupport", mutexSupport).build());
        }
        public boolean obtainSpecificPort(int portNumber) {
            return LocalhostMachineProvisioningLocation.obtainSpecificPort(getAddress(), portNumber);
        }
        public int obtainPort(PortRange range) {
            int r = LocalhostMachineProvisioningLocation.obtainPort(getAddress(), range);
            synchronized (portsObtained) {
                if (r>0) portsObtained.add(r);
            }
            return r;
        }
        public void releasePort(int portNumber) {
            synchronized (portsObtained) {
                portsObtained.remove((Object)portNumber);
            }
            LocalhostMachineProvisioningLocation.releasePort(getAddress(), portNumber);
        }
        
        @Override
        public OsDetails getOsDetails() {
            return new BasicOsDetails.Factory().newLocalhostInstance();
        }
    }
}
