package brooklyn.entity.messaging.rabbit;

import static brooklyn.entity.basic.lifecycle.CommonCommands.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.basic.AbstractSoftwareProcessSshDriver
import brooklyn.entity.messaging.amqp.AmqpServer
import brooklyn.location.basic.SshMachineLocation
import brooklyn.util.NetworkUtils

/**
 * TODO javadoc
 */
public class RabbitSshDriver extends AbstractSoftwareProcessSshDriver implements RabbitDriver {

    private static final Logger log = LoggerFactory.getLogger(RabbitSshDriver.class);

    public RabbitSshDriver(RabbitBroker entity, SshMachineLocation machine) {
        super(entity, machine)
    }

    @Override
    protected String getLogFileLocation() { "${runDir}/${entity.id}.log"; }

    public Integer getAmqpPort() { entity.getAttribute(AmqpServer.AMQP_PORT) }

    public String getVirtualHost() { entity.getAttribute(AmqpServer.VIRTUAL_HOST_NAME) }

    public String getErlangVersion() { entity.getConfig(RabbitBroker.ERLANG_VERSION) }

    @Override
    public void install() {
        String url = "http://www.rabbitmq.com/releases/rabbitmq-server/v${version}/rabbitmq-server-generic-unix-${version}.tar.gz";
        String saveAs = "rabbitmq-server-generic-unix-${version}.tar.gz";

        List<String> commands = ImmutableList.builder()
                .add(installPackage("erlang", // NOTE only 'port' states the version of Erlang used, maybe remove this constraint?
                        apt:"erlang-nox erlang-dev",
                        port:"erlang@${erlangVersion}+ssl"))
                .addAll(CommonCommands.downloadUrlAs(url, getEntityVersionLabel("/"), saveAs))
                .add(CommonCommands.installExecutable("tar"))
                .add(format("tar xvzf %s",saveAs))
                .build();

        newScript(INSTALLING).
                failOnNonZeroResultCode().
                body.append(commands).execute();
    }

    @Override
    public void customize() {
        NetworkUtils.checkPortsValid(amqpPort:amqpPort);
        newScript(CUSTOMIZING)
                .body.append(
                    "cp -R ${installDir}/rabbitmq_server-${version}/* .",
                )
                .execute()
    }

    @Override
    public void launch() {
        newScript(LAUNCHING, usePidFile:false)
            .body.append(
                "nohup ./sbin/rabbitmq-server > console-out.log 2> console-err.log &",
                "for i in {1..10}\n" +
                    "do\n" +
                     "    grep 'broker running' console-out.log && exit\n" +
                     "    sleep 1\n" +
                     "done",
                "echo \"Couldn't determine if rabbitmq-server is running\"",
                "exit 1"
            ).execute()
    }

    @Override
    public void configure() {
        newScript(CUSTOMIZING)
            .body.append(
                "./sbin/rabbitmqctl add_vhost ${entity.virtualHost}",
                "./sbin/rabbitmqctl set_permissions -p ${entity.virtualHost} guest \".*\" \".*\" \".*\"",
            ).execute()
    }

    public String getPidFile() { "rabbitmq.pid" }
    
    @Override
    public boolean isRunning() {
        newScript(CHECK_RUNNING, usePidFile:false)
                .body.append("./sbin/rabbitmqctl -q status")
                .execute() == 0;
    }


    @Override
    public void stop() {
        newScript(STOPPING, usePidFile:false)
                .body.append("./sbin/rabbitmqctl stop")
                .execute()
    }

    @Override
    public void kill() {
        stop(); // TODO No pid file to easily do `kill -9`
    }

    public Map<String, String> getShellEnvironment() {
        Map result = super.getShellEnvironment()
        result << [
            RABBITMQ_HOME: "${runDir}",
            RABBITMQ_LOG_BASE: "${runDir}",
            RABBITMQ_NODENAME: "${entity.id}",
            RABBITMQ_NODE_PORT: "${amqpPort}",
            RABBITMQ_PID_FILE: "${runDir}/${pidFile}"
        ]
    }
}