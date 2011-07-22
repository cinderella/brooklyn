package brooklyn.example

import brooklyn.entity.Entity
import brooklyn.entity.basic.AbstractApplication
import brooklyn.entity.basic.AbstractEntity
import brooklyn.entity.basic.JavaApp
import brooklyn.entity.group.DynamicCluster
import brooklyn.entity.group.DynamicFabric
import brooklyn.entity.proxy.nginx.NginxController
import brooklyn.entity.trait.Startable
import brooklyn.entity.webapp.JavaWebApp
import brooklyn.entity.webapp.tomcat.TomcatServer
import brooklyn.location.Location
import brooklyn.location.MachineProvisioningLocation
import brooklyn.location.basic.FixedListMachineProvisioningLocation
import brooklyn.location.basic.SshMachineLocation
import brooklyn.location.basic.aws.AWSCredentialsFromEnv
import brooklyn.location.basic.aws.AwsLocation
import brooklyn.policy.Policy

import com.google.common.base.Preconditions



/**
 * The application demonstrates the following:
 * <ul><li>dynamic clusters of web application servers</li>
 * <li>multiple geographic locations</li>
 * <li>use of any anycast DNS provider to router users to the closest cluster of web servers</li>
 * <li>resizing the clusters to meet client demand</li></ul>
 */
public class MultiLocationWebAppDemo extends AbstractApplication implements Startable {

    /**
     * This group contains all the sub-groups and entities that go in to a single location.
     * These are:
     * <ul><li>a @{link DynamicCluster} of @{link JavaWebApp}s</li>
     * <li>a cluster controller</li>
     * <li>a @{link Policy} to resize the DynamicCluster</li></ul>
     */
    private static class WebClusterEntity extends AbstractEntity implements Startable {
        private static final String springTravelPath
        private static final String warName = "swf-booking-mvc.war"

        private DynamicCluster cluster
        private NginxController controller
        private Policy policy

        static {
            URL resource = MultiLocationWebAppDemo.class.getClassLoader().getResource(warName)
            Preconditions.checkState resource != null, "Unable to locate resource $warName"
            springTravelPath = resource.getPath()
        }

        WebClusterEntity(Map props, Entity owner) {
            super(props, owner)

            cluster = new DynamicCluster(newEntity: { properties ->
                def server = new TomcatServer(properties)
                server.setConfig(JavaApp.SUGGESTED_JMX_PORT, 32199)
                server.setConfig(JavaWebApp.SUGGESTED_HTTP_PORT, 8080)
                server.setConfig(TomcatServer.SUGGESTED_SHUTDOWN_PORT, 31880)
                server.setConfig(JavaWebApp.WAR, springTravelPath)
                return server;
            }, this)
            cluster.setConfig(DynamicCluster.INITIAL_SIZE, 0)

            controller = new NginxController(
                owner: this,
                cluster: cluster,
                domain: 'localhost',
                port: 8000,
                portNumberSensor: JavaWebApp.HTTP_PORT
            )

            // FIXME: write this policy
//            policy = new WatermarkResizingPolicy()
//            policy.setConfig(WatermarkResizingPolicy.SENSOR, JavaWebApp.AVG_REQUESTS_PER_SECOND)
//            policy.setConfig(WatermarkResizingPolicy.LOW_WATER_MARK, 10)
//            policy.setConfig(WatermarkResizingPolicy.HIGH_WATER_MARK, 100)
        }

        // FIXME: why am I implementing these?
        void start(Collection<? extends Location> locations) {
            controller.start(locations)
            cluster.start(locations)
            // FIXME: register nginx' IP address with geo DNS
        }
        void stop() {
            controller.stop()
            cluster.stop()
        }
        void restart() {
            throw new UnsupportedOperationException()
        }
    }

    MultiLocationWebAppDemo(Map props=[:], Entity owner=null) {
        super(props, owner)
        
        new DynamicFabric(newEntity: { properties -> return new WebClusterEntity(properties) }, this)
    }
    
    @Override
    public void restart() {
        throw new UnsupportedOperationException()
    }
    
    public static void main(String[] args) {
        AwsLocation awsUsEastLocation = newAwsUsEastLocation()
        FixedListMachineProvisioningLocation montereyEastLocation = newMontereyEastLocation()
        
        // FIXME: start the web management console here
        MultiLocationWebAppDemo app = new MultiLocationWebAppDemo()
        app.start([montereyEastLocation, awsUsEastLocation])
        
        System.in.read()
        app.stop()
    }

    private static AwsLocation newAwsUsEastLocation() {
        final String REGION_NAME = "us-east-1" // "eu-west-1"
        final String IMAGE_ID = REGION_NAME+"/"+"ami-0859bb61" // "ami-d7bb90a3"
        final String IMAGE_OWNER = "411009282317"
        final String SSH_PUBLIC_KEY_PATH = "/Users/aled/id_rsa.junit.pub"
        final String SSH_PRIVATE_KEY_PATH = "/Users/aled/id_rsa.junit.private"
        
        AWSCredentialsFromEnv creds = new AWSCredentialsFromEnv();
        AwsLocation result = new AwsLocation(identity:creds.getAWSAccessKeyId(), credential:creds.getAWSSecretKey(), providerLocationId:REGION_NAME)
        result.setTagMapping([MyEntityType:[
                imageId:IMAGE_ID,
                providerLocationId:REGION_NAME,
                sshPublicKey:new File(SSH_PUBLIC_KEY_PATH),
                sshPrivateKey:new File(SSH_PRIVATE_KEY_PATH),
            ]]) //, imageOwner:IMAGE_OWNER]])
        return result
    }
    
    private static FixedListMachineProvisioningLocation newMontereyEastLocation() {
        // The definition of the Monterey East location
        final Collection<SshMachineLocation> MONTEREY_EAST_PUBLIC_ADDRESSES = [
                '216.48.127.224', '216.48.127.225', // east1a/b
                '216.48.127.226', '216.48.127.227', // east2a/b
                '216.48.127.228', '216.48.127.229', // east3a/b
                '216.48.127.230', '216.48.127.231', // east4a/b
                '216.48.127.232', '216.48.127.233', // east5a/b
                '216.48.127.234', '216.48.127.235'  // east6a/b
                ].collect { new SshMachineLocation(address: InetAddress.getByName(it), userName: 'cdm') }
                
        MachineProvisioningLocation<SshMachineLocation> result =
            new FixedListMachineProvisioningLocation<SshMachineLocation>(machines: MONTEREY_EAST_PUBLIC_ADDRESSES)
        return result
    }
}
