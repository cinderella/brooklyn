package brooklyn.entity.database.derby

import java.util.Collection
import java.util.Map

import javax.management.InstanceNotFoundException
import javax.management.ObjectName

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.Entity
import brooklyn.entity.basic.AbstractEntity
import brooklyn.entity.basic.Attributes
import brooklyn.entity.basic.legacy.JavaApp
import brooklyn.entity.basic.lifecycle.legacy.SshBasedAppSetup
import brooklyn.entity.database.Database
import brooklyn.entity.database.Schema
import brooklyn.event.adapter.SensorRegistry
import brooklyn.event.adapter.legacy.OldJmxSensorAdapter
import brooklyn.event.adapter.legacy.ValueProvider
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.event.basic.BasicConfigKey
import brooklyn.location.PortRange
import brooklyn.location.basic.SshMachineLocation

import com.google.common.base.Preconditions

/**
 * An {@link Entity} that represents a single Derby SQL database server instance.
 *
 * TODO work in progress
 */
public class DerbyDatabase extends JavaApp implements Database {
    private static final Logger log = LoggerFactory.getLogger(DerbyDatabase.class)

    public static final BasicConfigKey<PortRange> SUGGESTED_JDBC_PORT = [PortRange, "derby.jdbcPort", "Suggested JDBC port" ]
    public static final BasicConfigKey<String> VIRTUAL_HOST_NAME = [String, "derby.virtualHost", "Derby virtual host name" ]

    public static final BasicAttributeSensor<Integer> JDBC_PORT = [ Integer, "jdbc.port", "JDBC port" ]

    String virtualHost
    Collection<String> schemaNames = []
    Map<String, DerbySchema> schemas = [:]

    public DerbyDatabase(Map properties=[:]) {
        super(properties)
        virtualHost = getConfig(VIRTUAL_HOST_NAME) ?: properties.virtualHost ?: "localhost"
        setConfig(VIRTUAL_HOST_NAME, virtualHost)

        setAttribute(Attributes.JMX_USER, properties.user ?: "admin")
        setAttribute(Attributes.JMX_PASSWORD, properties.password ?: "admin")

        if (properties.schema) schemaNames.add properties.schema
        if (properties.schemas) schemaNames.addAll properties.schemas
    }

    public SshBasedAppSetup newDriver(SshMachineLocation machine) {
        return DerbySetup.newInstance(this, machine)
    }

    public void connectSensors() {
        super.connectSensors()
		
        sensorRegistry.addSensor(JavaApp.SERVICE_UP, { computeNodeUp() } as ValueProvider)
    }

    @Override
    public void postStart() {
        super.postStart()
        schemaNames.each { String name -> createSchema(name) }
    }

    @Override
    public void preStop() {
    	super.preStop()
        schemas.each { String name, DerbySchema schema -> schema.destroy() }
    }

    public void createSchema(String name, Map properties=[:]) {
        properties.owner = this
        properties.name = name
        schemas.put name, new DerbySchema(properties)
    }

    public Collection<Schema> getSchemas() {
        return schemas.values()
    }
    
    public void addSchema(Schema schema) {
        schemas.put schema.name, schema
	}
    
    public void removeSchema(String schemaName) {
        schemas.remove(schemaName)
    }

    @Override
    public Collection<String> toStringFieldsToInclude() {
        return super.toStringFieldsToInclude() + ['jdbcPort']
    }

    protected boolean computeNodeUp() {
        ValueProvider<String> provider = jmxAdapter.newAttributeProvider("org.apache.derby:type=ServerInformation,name=ServerInformation", "ProductVersion")
        try {
            String productVersion = provider.compute()
	        return (productVersion == getAttribute(Attributes.VERSION))
        } catch (InstanceNotFoundException infe) {
            return false
        }
    }
}

public class DerbySchema extends AbstractEntity implements Schema {
    String virtualHost
    String name

    protected ObjectName virtualHostManager
    protected ObjectName exchange

    transient OldJmxSensorAdapter jmxAdapter
    transient SensorRegistry sensorRegistry

    public DerbySchema(Map properties=[:], Entity owner=null) {
        super(properties, owner)

        Preconditions.checkNotNull properties.name, "Name must be specified"
        name = properties.name

        virtualHost = getConfig(DerbyDatabase.VIRTUAL_HOST_NAME) ?: properties.virtualHost ?: "localhost"
        setConfig(DerbyDatabase.VIRTUAL_HOST_NAME, virtualHost)
        virtualHostManager = new ObjectName("org.apache.derby:type=VirtualHost.VirtualHostManager,VirtualHost=\"${virtualHost}\"")
        init()

        jmxAdapter = ((DerbyDatabase) this.owner).jmxAdapter
        sensorRegistry = new SensorRegistry(this)

        create()
    }
    
    public void init() {
        setConfig SCHEMA_NAME, name
        exchange = new ObjectName("org.apache.derby:type=VirtualHost.Exchange,VirtualHost=\"${virtualHost}\",name=\"amq.direct\",ExchangeType=direct")
    }

    public void addJmxSensors() {
        String schema = "org.apache.derby:type=VirtualHost.Schema,VirtualHost=\"${virtualHost}\",name=\"${name}\""
        sensorRegistry.addSensor(SCHEMA_DEPTH, jmxAdapter.newAttributeProvider(schema, "SchemaDepth"))
        sensorRegistry.addSensor(MESSAGE_COUNT, jmxAdapter.newAttributeProvider(schema, "MessageCount"))
    }

    public void removeJmxSensors() {
        String schema = "org.apache.derby:type=VirtualHost.Schema,VirtualHost=\"${virtualHost}\",name=\"${name}\""
        sensorRegistry.removeSensor(SCHEMA_DEPTH)
        sensorRegistry.removeSensor(MESSAGE_COUNT)
    }

    public void create() {
        jmxAdapter.operation(virtualHostManager, "createNewSchema", name, owner.getAttribute(Attributes.JMX_USER), true)
        jmxAdapter.operation(exchange, "createNewBinding", name, name)
        addJmxSensors()
    }

    public void load() {
        jmxAdapter.operation(exchange, "removeBinding", name, name)
        jmxAdapter.operation(virtualHostManager, "deleteSchema", name)
        removeJmxSensors()
    }

    /**
     * Return the JDBC connection URL for the schema.
     */
    public String getConnectionUrl() { return String.format("jdbc:derby:%s", name) }

    @Override
    public void destroy() {
		sensorRegistry.close()
        super.destroy()
	}

    @Override
    public Collection<String> toStringFieldsToInclude() {
        return super.toStringFieldsToInclude() + ['name']
    }
}
