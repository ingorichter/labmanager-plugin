package hudson.plugins.labmanager;

import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.jvnet.hudson.test.HudsonTestCase;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

/**
 * Created by IntelliJ IDEA.
 * User: irichter
 * Date: 1/5/12
 * Time: 3:54 PM
 * To change this template use File | Settings | File Templates.
 */
public class LabManagerVirtualMachineLauncherTest extends HudsonTestCase {
    private Node testLMNode;
    private Cloud testCloud;
    private String vmName = "Linux Test VM";

    @BeforeClass
    public void setUp() throws Exception {
        super.setUp();

        // add LabManager Test Cloud
        testCloud = new LabManager("http://labhost:8080", "Test Lab Manager", "TestOrg", "Test Workspace",
                "Test Configuration", "TestUser", "Test Password", 0);

        Hudson.getInstance().clouds.add(testCloud);

        // create a LM test node
        testLMNode = new LabManagerVirtualMachineSlave("Lab VM Linux Node", "Node Description", "/home/jenkins", "2",
                null, "Linux", null, RetentionStrategy.NOOP,
                new ArrayList<NodeProperty<LabManagerVirtualMachineSlave>>(), "LabManager Description",
                "LabManager VM Name", "Idle Option", false, "60", false);
        Hudson.getInstance().addNode(testLMNode);
    }
    
    @AfterClass
    public void tearDown() throws Exception {
        Hudson.getInstance().clouds.remove(testCloud);
        Hudson.getInstance().removeNode(testLMNode);

        super.tearDown();
    }

    @Test
    public void testFindOurLmInstance() throws Exception {
        LabManagerVirtualMachineLauncher labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (null, "Test Lab Manager", "Linux Test VM", "idle option", false, "launchDelay", false);
        LabManager ourLmInstance = labManagerVirtualMachineLauncher.findOurLmInstance();

        assertNotNull(ourLmInstance);
    }

    @Test
    public void testFindLMNode() {
        List<Node> nodeList = Hudson.getInstance().getNodes();

        assertNotNull(nodeList);

        Node ourNode = null;
        for (Node node : nodeList) {
            if (node instanceof LabManagerVirtualMachineSlave) {
                if (node.getNodeName().equals("Lab VM Linux Node")) {
                    ourNode = node;
                }
            }
        }

        assertNotNull("Test node must be present", ourNode);
        assertSame("Must be the same object", ourNode, testLMNode);
    }

    @Test
    public void testLaunchMachine() throws IOException, InterruptedException {
        // SOAP Interface mock
        final LabManager_x0020_SOAP_x0020_interfaceStub mockSOAPInterface = 
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.class);

        // Prepare Configuration mock
        LabManager_x0020_SOAP_x0020_interfaceStub.Configuration configuration =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.Configuration.class);
        when(configuration.getId()).thenReturn(123456);

        // prepare single configuration response
        LabManager_x0020_SOAP_x0020_interfaceStub.GetSingleConfigurationByNameResponse getSingleConfigurationByNameResponse =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.GetSingleConfigurationByNameResponse.class);
        when(getSingleConfigurationByNameResponse.getGetSingleConfigurationByNameResult()).thenReturn(configuration);

        // prepare the machine mock
        LabManager_x0020_SOAP_x0020_interfaceStub.Machine mockMachine =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.Machine.class);
        when(mockMachine.getName()).thenReturn(vmName);
        when(mockMachine.getStatus()).thenReturn(2); // STATUS_ON

        final LabManager_x0020_SOAP_x0020_interfaceStub.GetMachineByNameResponse mockGetMachineByNameResponse =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.GetMachineByNameResponse.class);
        when(mockGetMachineByNameResponse.getGetMachineByNameResult()).thenReturn(mockMachine);

        // prepare the SOAPInterface to return our mock objects
        when(mockSOAPInterface.getSingleConfigurationByName(
                any(LabManager_x0020_SOAP_x0020_interfaceStub.GetSingleConfigurationByName.class),
                any(LabManager_x0020_SOAP_x0020_interfaceStub.AuthenticationHeaderE.class))).thenReturn
                (getSingleConfigurationByNameResponse);
        when(mockSOAPInterface.getMachineByName(
                any(LabManager_x0020_SOAP_x0020_interfaceStub.GetMachineByName.class),
                any(LabManager_x0020_SOAP_x0020_interfaceStub.AuthenticationHeaderE.class))).thenReturn(mockGetMachineByNameResponse);
        
        // prepare our LabManager to return the mock SOAPInterface
        final LabManager mockLabManager = mock(LabManager.class);
        when(mockLabManager.getLmStub()).thenReturn(mockSOAPInterface);
        when(mockLabManager.markOneSlaveOnline(vmName)).thenReturn(1);

        ComputerLauncher mockComputerLauncher = mock(ComputerLauncher.class);

        LabManagerVirtualMachineLauncher labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (mockComputerLauncher, "TestLM", vmName, "idle option", false, "0", false)
        {
            public LabManager findOurLmInstance() throws RuntimeException {
                return mockLabManager;
            }
        };

        // prepare Mocks
        SlaveComputer mockSlaveComputer = mock(SlaveComputer.class);
        when(mockSlaveComputer.getDisplayName()).thenReturn(vmName);
        
        TaskListener mockTaskListener = mock(TaskListener.class);
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        when(mockTaskListener.getLogger()).thenReturn(
                new PrintStream(byteArrayOutputStream));

        // now the call to test
        labManagerVirtualMachineLauncher.launch(mockSlaveComputer, mockTaskListener);

        assertEquals("Log output should be", "Starting Virtual Machine...\n", byteArrayOutputStream.toString());
        verify(mockTaskListener).getLogger();
        verify(mockSlaveComputer).getDisplayName();
        verify(mockComputerLauncher).launch(mockSlaveComputer, mockTaskListener);
        verify(mockLabManager).markOneSlaveOffline(vmName);
    }

    @Test
    public void testGetLauncherForMachineSameIP() {
        SSHLauncher mockComputerSSHLauncher = mock(SSHLauncher.class);
        when(mockComputerSSHLauncher.getHost()).thenReturn("10.20.30.40");

        LabManagerVirtualMachineLauncher labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (mockComputerSSHLauncher, "TestLM", vmName, "idle option", false, "0", false);

        // prepare the machine mock
        LabManager_x0020_SOAP_x0020_interfaceStub.Machine mockMachine =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.Machine.class);
        when(mockMachine.getStatus()).thenReturn(2); // STATUS_ON
        when(mockMachine.getExternalIP()).thenReturn("10.20.30.40"); //new IP Address

        final ComputerLauncher updatedLauncherDelegateForMachine =
                labManagerVirtualMachineLauncher.getDecoratedLauncherDelegateForMachine(mockMachine);

        assertSame("Must be the same launcher instance", mockComputerSSHLauncher, updatedLauncherDelegateForMachine);
    }

    @Test
    public void testGetLauncherForMachineNewIP() {
        SSHLauncher mockComputerSSHLauncher = mock(SSHLauncher.class);
        when(mockComputerSSHLauncher.getHost()).thenReturn("10.20.30.40");

        LabManagerVirtualMachineLauncher labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (mockComputerSSHLauncher, "TestLM", vmName, "idle option", false, "0", true);

        // prepare the machine mock
        LabManager_x0020_SOAP_x0020_interfaceStub.Machine mockMachine =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.Machine.class);
        when(mockMachine.getStatus()).thenReturn(2); // STATUS_ON
        when(mockMachine.getExternalIP()).thenReturn("30.30.30.30"); //new IP Address

        final ComputerLauncher updatedLauncherDelegateForMachine =
                labManagerVirtualMachineLauncher.getDecoratedLauncherDelegateForMachine(mockMachine);

        assertNotSame("Must not be the same launcher instance", mockComputerSSHLauncher, 
                updatedLauncherDelegateForMachine);
        assertTrue("Must be instance of SSHLauncher", updatedLauncherDelegateForMachine instanceof
                SSHLauncher);
        assertEquals("Must be new IP 30.30.30.30", "30.30.30.30", ((SSHLauncher)updatedLauncherDelegateForMachine)
                .getHost());
    }

    @Test
    public void testUndeployMachine() throws RemoteException {
        // SOAP Interface mock
        final LabManager_x0020_SOAP_x0020_interfaceStub mockSOAPInterface =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.class);

        // Prepare Configuration mock
        LabManager_x0020_SOAP_x0020_interfaceStub.Configuration configuration =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.Configuration.class);
        when(configuration.getId()).thenReturn(123456);

        // prepare single configuration response
        LabManager_x0020_SOAP_x0020_interfaceStub.GetSingleConfigurationByNameResponse getSingleConfigurationByNameResponse =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.GetSingleConfigurationByNameResponse.class);
        when(getSingleConfigurationByNameResponse.getGetSingleConfigurationByNameResult()).thenReturn(configuration);

        // prepare the machine mock
        LabManager_x0020_SOAP_x0020_interfaceStub.Machine mockMachine =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.Machine.class);
        when(mockMachine.getName()).thenReturn(vmName);
        when(mockMachine.getId()).thenReturn(123567);
        when(mockMachine.getStatus()).thenReturn(2); // STATUS_ON

        final LabManager_x0020_SOAP_x0020_interfaceStub.GetMachineByNameResponse mockGetMachineByNameResponse =
                mock(LabManager_x0020_SOAP_x0020_interfaceStub.GetMachineByNameResponse.class);
        when(mockGetMachineByNameResponse.getGetMachineByNameResult()).thenReturn(mockMachine);

        // prepare the SOAPInterface to return our mock objects
        when(mockSOAPInterface.getSingleConfigurationByName(
                any(LabManager_x0020_SOAP_x0020_interfaceStub.GetSingleConfigurationByName.class),
                any(LabManager_x0020_SOAP_x0020_interfaceStub.AuthenticationHeaderE.class))).thenReturn
                (getSingleConfigurationByNameResponse);
        when(mockSOAPInterface.getMachineByName(
                any(LabManager_x0020_SOAP_x0020_interfaceStub.GetMachineByName.class),
                any(LabManager_x0020_SOAP_x0020_interfaceStub.AuthenticationHeaderE.class))).thenReturn(mockGetMachineByNameResponse);

        // prepare our LabManager to return the mock SOAPInterface
        final LabManager mockLabManager = mock(LabManager.class);
        when(mockLabManager.getLmStub()).thenReturn(mockSOAPInterface);
        when(mockLabManager.markOneSlaveOnline(vmName)).thenReturn(1);

        ComputerLauncher mockComputerLauncher = mock(ComputerLauncher.class);

        LabManagerVirtualMachineLauncher labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (mockComputerLauncher, "TestLM", vmName, "Undeploy", false, "0", false)
        {
            public LabManager findOurLmInstance() throws RuntimeException {
                return mockLabManager;
            }
        };

        // prepare Mocks
        SlaveComputer mockSlaveComputer = mock(SlaveComputer.class);
        when(mockSlaveComputer.getDisplayName()).thenReturn(vmName);

        TaskListener mockTaskListener = mock(TaskListener.class);
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        when(mockTaskListener.getLogger()).thenReturn(
                new PrintStream(byteArrayOutputStream));

        labManagerVirtualMachineLauncher.afterDisconnect(mockSlaveComputer, mockTaskListener);

        assertEquals("Log output should be", "Running disconnect procedure...\nShutting down Virtual Machine...\n", byteArrayOutputStream.toString());
        verify(mockTaskListener, times(2)).getLogger();
        verify(mockSlaveComputer).getDisplayName();
        verify(mockComputerLauncher).afterDisconnect(mockSlaveComputer, mockTaskListener);
        
        // check if machinePerformAction was called with the correct arguments

        ArgumentCaptor<LabManager_x0020_SOAP_x0020_interfaceStub.MachinePerformAction> machinePerformAction
                = ArgumentCaptor.forClass(LabManager_x0020_SOAP_x0020_interfaceStub.MachinePerformAction.class);
        verify(mockSOAPInterface).machinePerformAction(machinePerformAction.capture(), any(LabManager_x0020_SOAP_x0020_interfaceStub.AuthenticationHeaderE.class));
        assertEquals("Action must be 13", 13, machinePerformAction.getValue().getAction());
        assertEquals("Machine ID must be 123567", 123567, machinePerformAction.getValue().getMachineId());
        // never consume exception and don't tell us
        verify(mockTaskListener, never()).fatalError(any(String.class), any(Throwable.class));
    }

    @Test
    public void testGetIdleAction() {
        //create instance with invalid Action => should default to MACHINE_ACTION_SUSPEND
        LabManagerVirtualMachineLauncher labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (null /* no launcher for test*/, "TestLM", vmName, "idle option", false, "0", false);

        assertEquals("Default idle action should be SUSPEND", LabManagerVirtualMachineLauncher
                .MACHINE_ACTION_SUSPEND,
                labManagerVirtualMachineLauncher.getIdleAction());

        labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (null /* no launcher for test*/, "TestLM", vmName, "Shutdown", false, "0", false);

        assertEquals("Idle action should be SHUTDOWN", LabManagerVirtualMachineLauncher
                .MACHINE_ACTION_SHUTDOWN,
                labManagerVirtualMachineLauncher.getIdleAction());

        labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (null /* no launcher for test*/, "TestLM", vmName, "Shutdown and Revert", false, "0", false);

        assertEquals("Idle action should be REVERT", LabManagerVirtualMachineLauncher
                .MACHINE_ACTION_REVERT,
                labManagerVirtualMachineLauncher.getIdleAction());

        labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (null /* no launcher for test*/, "TestLM", vmName, "Suspend", false, "0", false);

        assertEquals("Idle action should be SUSPEND", LabManagerVirtualMachineLauncher
                .MACHINE_ACTION_SUSPEND,
                labManagerVirtualMachineLauncher.getIdleAction());

        labManagerVirtualMachineLauncher = new LabManagerVirtualMachineLauncher
                (null /* no launcher for test*/, "TestLM", vmName, "Undeploy", false, "0", false);

        assertEquals("Idle action should be UNDEPLOY", LabManagerVirtualMachineLauncher
                .MACHINE_ACTION_UNDEPLOY,
                labManagerVirtualMachineLauncher.getIdleAction());
    }
}