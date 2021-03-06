package brooklyn.cli;

import static org.testng.Assert.assertTrue;
import groovy.lang.GroovyClassLoader;

import org.iq80.cli.Cli;
import org.iq80.cli.ParseException;
import org.testng.annotations.Test;

import brooklyn.cli.Main.BrooklynCommand;
import brooklyn.cli.Main.HelpCommand;
import brooklyn.cli.Main.LaunchCommand;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.util.ResourceUtils;

public class CliTest {

    @Test
    public void testLoadApplicationFromClasspath() throws Exception {
        LaunchCommand launchCommand = new Main.LaunchCommand();
        ResourceUtils resourceUtils = new ResourceUtils(this);
        GroovyClassLoader loader = new GroovyClassLoader(CliTest.class.getClassLoader());
        String appName = ExampleApp.class.getName();
        AbstractApplication app = launchCommand.loadApplicationFromClasspathOrParse(resourceUtils, loader, appName);
        assertTrue(app instanceof ExampleApp, "app="+app);
    }

    @Test
    public void testLoadApplicationByParsingFile() throws Exception {
        LaunchCommand launchCommand = new Main.LaunchCommand();
        ResourceUtils resourceUtils = new ResourceUtils(this);
        GroovyClassLoader loader = new GroovyClassLoader(CliTest.class.getClassLoader());
        String appName = "ExampleAppInFile.groovy"; // file found in src/test/resources (contains empty app)
        AbstractApplication app = launchCommand.loadApplicationFromClasspathOrParse(resourceUtils, loader, appName);
        assertTrue(app.getClass().getName().equals("ExampleAppInFile"), "app="+app);
    }
    
    @Test
    public void testLaunchCommand() throws ParseException {
        Cli<BrooklynCommand> cli = Main.buildCli();
        BrooklynCommand command = cli.parse("launch", "--app", "my.App", "--location", "localhost");
        assertTrue(command instanceof LaunchCommand, ""+command);
        String details = command.toString();
        assertTrue(details.contains("app=my.App"), details);   
        assertTrue(details.contains("script=null"), details);
        assertTrue(details.contains("location=localhost"), details);
        assertTrue(details.contains("port=8081"), details);
        assertTrue(details.contains("noConsole=false"), details);
        assertTrue(details.contains("noShutdwonOnExit=false"), details);
    }

    @Test
    public void testAppOptionIsOptional() throws ParseException {
        Cli<BrooklynCommand> cli = Main.buildCli();
        cli.parse("launch", "blah", "my.App");
    }
    
    public void testHelpCommand() {
        Cli<BrooklynCommand> cli = Main.buildCli();
        BrooklynCommand command = cli.parse("help");
        assertTrue(command instanceof HelpCommand);
        command = cli.parse();
        assertTrue(command instanceof HelpCommand);
    }
    
    // An empty app to be used for testing
    @SuppressWarnings("serial")
    public static class ExampleApp extends AbstractApplication { }
}
