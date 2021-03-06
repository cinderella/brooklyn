package brooklyn.entity.java

import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import java.util.concurrent.TimeUnit

import javax.management.openmbean.CompositeData

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.enricher.RollingTimeWindowMeanEnricher
import brooklyn.enricher.TimeFractionDeltaEnricher
import brooklyn.entity.basic.EntityLocal
import brooklyn.entity.basic.SoftwareProcessEntity
import brooklyn.event.adapter.JmxSensorAdapter

class JavaAppUtils {

    private static final Logger log = LoggerFactory.getLogger(SoftwareProcessEntity.class)
    
    /* TODO we should switch to:
     * 
     *  attribute("HeapMemoryUsage").then( { CompositeData m -> MemoryUsage.from(m) }).with {
     *      subscribe(UsesJavaMXBeans.USED_HEAP_MEMORY, { it?.getUsed() });
     *      subscribe(...);
     *  }
     */
    public static void connectMXBeanSensors(EntityLocal entity, JmxSensorAdapter jmxAdapter) {
        
        if (entity.getConfig(UsesJavaMXBeans.MXBEAN_STATS_ENABLED)) {
            jmxAdapter.objectName(ManagementFactory.MEMORY_MXBEAN_NAME).with {
                attribute("HeapMemoryUsage").subscribe(UsesJavaMXBeans.USED_HEAP_MEMORY, { CompositeData m -> MemoryUsage.from(m)?.getUsed() });
                attribute("HeapMemoryUsage").subscribe(UsesJavaMXBeans.INIT_HEAP_MEMORY, { CompositeData m -> MemoryUsage.from(m)?.getInit() });
                attribute("HeapMemoryUsage").subscribe(UsesJavaMXBeans.COMMITTED_HEAP_MEMORY, { CompositeData m -> MemoryUsage.from(m)?.getCommitted() });
                attribute("HeapMemoryUsage").subscribe(UsesJavaMXBeans.MAX_HEAP_MEMORY, { CompositeData m -> MemoryUsage.from(m)?.getMax() });
                attribute("NonHeapMemoryUsage").subscribe(UsesJavaMXBeans.NON_HEAP_MEMORY_USAGE, { CompositeData m -> MemoryUsage.from(m)?.getUsed() });
            }
            
            jmxAdapter.objectName(ManagementFactory.THREAD_MXBEAN_NAME).with {
                attribute("ThreadCount").subscribe(UsesJavaMXBeans.CURRENT_THREAD_COUNT);
                attribute("PeakThreadCount").subscribe(UsesJavaMXBeans.PEAK_THREAD_COUNT);
            }
            
            jmxAdapter.objectName(ManagementFactory.RUNTIME_MXBEAN_NAME).with {
                attribute(period:60*TimeUnit.SECONDS, "StartTime").subscribe(UsesJavaMXBeans.START_TIME);
                attribute("Uptime").subscribe(UsesJavaMXBeans.UP_TIME);
            }
            
            jmxAdapter.objectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME).with {
                attribute("ProcessCpuTime").subscribe(UsesJavaMXBeans.PROCESS_CPU_TIME);
                attribute("SystemLoadAverage").subscribe(UsesJavaMXBeans.SYSTEM_LOAD_AVERAGE);
                attribute(period:60*TimeUnit.SECONDS, "AvailableProcessors").subscribe(UsesJavaMXBeans.AVAILABLE_PROCESSORS);
                attribute(period:60*TimeUnit.SECONDS, "TotalPhysicalMemorySize").subscribe(UsesJavaMXBeans.TOTAL_PHYSICAL_MEMORY_SIZE);
                attribute(period:60*TimeUnit.SECONDS, "FreePhysicalMemorySize").subscribe(UsesJavaMXBeans.FREE_PHYSICAL_MEMORY_SIZE);
            }

            
            //FIXME: need a new type of adapter that maps multiple objectNames to a mapping
//            jmxAdapter.objectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",*").with {
//                attribute("SystemLoadAverage").subscribe(UsesJavaMXBeans.GARBAGE_COLLECTION_TIME, { def m -> log.info("XXXXXXX $m") });
//            }
        }
    }
    
    public static void connectJavaAppServerPolicies(EntityLocal entity) {
        entity.addEnricher(new TimeFractionDeltaEnricher<Long>(entity, UsesJavaMXBeans.PROCESS_CPU_TIME, 
                UsesJavaMXBeans.PROCESS_CPU_TIME_FRACTION, TimeUnit.NANOSECONDS));

        entity.addEnricher(new RollingTimeWindowMeanEnricher<Double>(entity,
                UsesJavaMXBeans.PROCESS_CPU_TIME_FRACTION, UsesJavaMXBeans.AVG_PROCESS_CPU_TIME_FRACTION,
                UsesJavaMXBeans.AVG_PROCESS_CPU_TIME_FRACTION_PERIOD));
    }
}
