package brooklyn.demo

import brooklyn.entity.basic.AbstractApplication
import java.util.Map

import brooklyn.entity.Entity
import brooklyn.entity.basic.AbstractApplication
import brooklyn.entity.basic.DynamicGroup
import brooklyn.entity.dns.geoscaling.GeoscalingDnsService
import brooklyn.entity.group.DynamicFabric
import brooklyn.entity.proxy.nginx.NginxController
import brooklyn.entity.webapp.DynamicWebAppCluster
import brooklyn.entity.webapp.JavaWebApp
import brooklyn.policy.ResizerPolicy

import com.google.common.base.Preconditions

/**
 * The application demonstrates the following:
 * 
 * <ul>
 * <li>Dynamic clusters of web application servers
 * <li>Multiple geographic locations
 * <li>Use of any geo-redirecting DNS provider to route users to their closest cluster of web servers
 * <li>Resizing the clusters to meet client demand
 * </ul>
 */
public class SpringTravel extends AbstractApplication {
    // XXX change these paths before running the demo
    private static final String SPRING_TRAVEL_PATH = "src/main/resources/swf-booking-mvc.war"

    final DynamicFabric fabric
    final DynamicGroup nginxEntities
    
    SpringTravel(Map props=[:]) {
        super(props)
        
        fabric = new DynamicFabric(
            [
                id : 'fabricID',
	            name : 'fabricName',
	            displayName : 'Fabric',
	            newEntity : { Map properties -> 
                    WebCluster cluster = new WebCluster(properties, fabric)
                    
                    ResizerPolicy policy = new ResizerPolicy(DynamicWebAppCluster.AVERAGE_REQUESTS_PER_SECOND)
                    policy.setMinSize(1)
                    policy.setMaxSize(5)
                    policy.setMetricLowerBound(10)
                    policy.setMetricUpperBound(100)
                    cluster.cluster.addPolicy(policy)
            
                    return cluster
                }
            ],
            this)
        fabric.setConfig(JavaWebApp.WAR, SPRING_TRAVEL_PATH)
        
        Preconditions.checkState fabric.displayName == "Fabric"

        nginxEntities = new DynamicGroup([:], this, { Entity e -> (e instanceof NginxController) })

        GeoscalingDnsService geoDns = new GeoscalingDnsService(
            config: [
                (GeoscalingDnsService.GEOSCALING_USERNAME): 'cloudsoft',
                (GeoscalingDnsService.GEOSCALING_PASSWORD): 'cl0uds0ft',
                (GeoscalingDnsService.GEOSCALING_PRIMARY_DOMAIN_NAME): 'geopaas.org',
                (GeoscalingDnsService.GEOSCALING_SMART_SUBDOMAIN_NAME): 'brooklyn',
            ],
            this)

        nginxEntities.rescanEntities()
        geoDns.setGroup(nginxEntities)
    }
}
