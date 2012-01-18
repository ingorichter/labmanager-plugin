package hudson.plugins.labmanager;

import org.junit.Before;
import org.junit.Test;
import org.jvnet.hudson.test.HudsonTestCase;

import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: irichter
 * Date: 1/9/12
 * Time: 12:11 PM
 * To change this template use File | Settings | File Templates.
 */
public class LabManagerVirtualMachineSlaveTest extends HudsonTestCase {
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Test
    public void testGetIdleOptions() throws Exception {
        LabManagerVirtualMachineSlave.DescriptorImpl descriptor = new LabManagerVirtualMachineSlave.DescriptorImpl();
        List<String> idleOptions = descriptor.getIdleOptions();

        org.junit.Assert.assertArrayEquals("Options have to be the same", new String[] {"Shutdown", "Shutdown and Revert", "Suspend", "Undeploy"}, idleOptions.toArray());
    }
}
