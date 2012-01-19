/**
 *  Copyright (C) 2010-2011 Mentor Graphics Corporation
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Based on the libvirt-plugin which is:
 *  Copyright (C) 2010, Byte-Code srl <http://www.byte-code.com>
 *
 * Date: Mar 04, 2010
 * Author: Marco Mornati<mmornati@byte-code.com>
 */
package hudson.plugins.labmanager;

import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub;
import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub.AuthenticationHeaderE;
import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub.Configuration;
import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub.ConfigurationDeploy;
import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub.GetMachineByName;
import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub.GetMachineByNameResponse;
import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub.GetSingleConfigurationByName;
import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub.GetSingleConfigurationByNameResponse;
import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub.Machine;
import com.vmware.labmanager.LabManager_x0020_SOAP_x0020_interfaceStub.MachinePerformAction;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.io.PrintStream;
import java.rmi.RemoteException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link ComputerLauncher} for Lab Manager that waits for the Virtual Machine
 * to really come up before proceeding to the real user-specified
 * {@link ComputerLauncher}.
 *
 * @author Tom Rini <tom_rini@mentor.com>
 */
public class LabManagerVirtualMachineLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(LabManagerVirtualMachineLauncher.class.getName());
    private ComputerLauncher delegate;
    private String lmDescription;
    private String vmName;
    private int idleAction;
    private Boolean overrideLaunchSupported;
    private int launchDelay;
    private boolean updateHostAddress;
    /**
     * Constants.
     */

    /* Machine status codes. */
    private static final int MACHINE_STATUS_UNDEPLOYED = 0;
    private static final int MACHINE_STATUS_OFF = 1;
    private static final int MACHINE_STATUS_ON = 2;
    private static final int MACHINE_STATUS_SUSPENDED = 3;
    private static final int MACHINE_STATUS_STUCK = 4;
    private static final int MACHINE_STATUS_INVALID = 128;

    /*
     * Machine action codes.
     */
    public static final int MACHINE_ACTION_NONE = 0;
    public static final int MACHINE_ACTION_ON = 1;
    public static final int MACHINE_ACTION_OFF = 2;
    public static final int MACHINE_ACTION_SUSPEND = 3;
    public static final int MACHINE_ACTION_RESUME = 4;
    public static final int MACHINE_ACTION_RESET = 5;
    public static final int MACHINE_ACTION_SNAPSHOT = 6;
    public static final int MACHINE_ACTION_REVERT = 7;
    public static final int MACHINE_ACTION_SHUTDOWN = 8;
    // this is part of the VM Lab Manager internal SOAP API
    public static final int MACHINE_ACTION_DEPLOY = 12;
    public static final int MACHINE_ACTION_UNDEPLOY = 13;

    /*
     * Fence mode for configuration deployment
     */
    private static final int FENCE_MODE_ALLOW_IN_AND_OUT = 4;  // allow in and out

    /**
     * @param delegate The real {@link ComputerLauncher} we have been passed.
     * @param lmDescription Human reable description of the Lab Manager
     * instance.
     * @param vmName The 'VM Name' field in the configuration in Lab Manager.
     * @param idleOption The choice of action to take when the slave is deemed
     * idle.
     * @param overrideLaunchSupported Boolean to set of we force
     * isLaunchSupported to always return True.
     * @param launchDelay How long to wait between bringing up the VM and
     * @param updateHostAddress Update the host address if the launcher starts
     * the slave by using a ssh connection.
     */
    @DataBoundConstructor
    public LabManagerVirtualMachineLauncher(ComputerLauncher delegate,
            String lmDescription, String vmName, String idleOption,
            Boolean overrideLaunchSupported, String launchDelay,
            boolean updateHostAddress) {
        super();
        this.delegate = delegate;
        this.lmDescription = lmDescription;
        this.vmName = vmName;
        if ("Shutdown".equals(idleOption))
            idleAction = MACHINE_ACTION_SHUTDOWN;
        else if ("Shutdown and Revert".equals(idleOption))
            idleAction = MACHINE_ACTION_REVERT;
        else if ("Undeploy".equals(idleOption))
            idleAction = MACHINE_ACTION_UNDEPLOY;
        else
            idleAction = MACHINE_ACTION_SUSPEND;

        this.overrideLaunchSupported = overrideLaunchSupported;
        this.launchDelay = Util.tryParseNumber(launchDelay, 60).intValue();
        this.updateHostAddress = updateHostAddress;
    }

    /**
     * Determine what LabManager object controls this slave.  Once we have
     * that we can call and get the information out that we need to perform
     * SOAP calls.
     */
    public LabManager findOurLmInstance() throws RuntimeException {
        if (lmDescription != null && vmName != null) {
            LabManager labmanager = null;
            for (Cloud cloud : Hudson.getInstance().clouds) {
                if (cloud instanceof LabManager && ((LabManager) cloud).getLmDescription().equals(lmDescription)) {
                    labmanager = (LabManager) cloud;
                    return labmanager;
                }
            }
        }
        LOGGER.log(Level.SEVERE, "Could not find our Lab Manager instance!");
        throw new RuntimeException("Could not find our Lab Manager instance!");
    }

    /**
     * We have stored inside of the LabManager object all of the information
     * needed to get the Machine object back out.  We cannot store the
     * machineId value itself so we work our way towards it by getting the
     * Configuration we know the machine lives in and then returning the
     * Machine object.  We know that the machine name is unique to the
     * configuration.
     */
    private Machine getMachine(LabManager labmanager)
            throws java.rmi.RemoteException {
        AuthenticationHeaderE lmAuth = labmanager.getLmAuth();
        LabManager_x0020_SOAP_x0020_interfaceStub lmStub = labmanager.getLmStub();

        final Configuration configuration = getSingleConfiguration(labmanager);

        final GetMachineByName getMachineByNameRequest = new GetMachineByName();
        getMachineByNameRequest.setConfigurationId(configuration.getId());
        getMachineByNameRequest.setName(this.vmName);
        GetMachineByNameResponse machineByName = 
                lmStub.getMachineByName(getMachineByNameRequest, lmAuth);
        Machine vm = machineByName.getGetMachineByNameResult();

        return vm;
    }

    private Configuration getSingleConfiguration(LabManager labmanager) throws java.rmi.RemoteException {
        AuthenticationHeaderE lmAuth = labmanager.getLmAuth();
        LabManager_x0020_SOAP_x0020_interfaceStub lmStub = labmanager.getLmStub();

        GetSingleConfigurationByName gscbnReq = new GetSingleConfigurationByName();
        gscbnReq.setName(labmanager.getLmConfiguration());
        final GetSingleConfigurationByNameResponse singleConfigurationByName =
                lmStub.getSingleConfigurationByName(gscbnReq, lmAuth);

        final Configuration configuration = singleConfigurationByName.getGetSingleConfigurationByNameResult();
        return configuration;
    }

    /**
     * Perform the specified action on the specified machine via SOAP.
     */
    private void performMachineAction(LabManager labmanager, Machine vm, int action)
            throws java.rmi.RemoteException {
        AuthenticationHeaderE lmAuth = labmanager.getLmAuth();
        LabManager_x0020_SOAP_x0020_interfaceStub lmStub = labmanager.getLmStub();

        MachinePerformAction mpaReq = new MachinePerformAction();
        mpaReq.setAction(action);
        mpaReq.setMachineId(vm.getId());
        /* We can't actually do anything here, problems come
         * as an exception I believe. */
        lmStub.machinePerformAction(mpaReq, lmAuth);
    }

    private void deployConfiguration(LabManager labmanager,
            Configuration configuration) throws RemoteException {
        final AuthenticationHeaderE lmAuth = labmanager.getLmAuth();
        final LabManager_x0020_SOAP_x0020_interfaceStub lmStub = labmanager.getLmStub();

        final ConfigurationDeploy configurationDeploy = new ConfigurationDeploy();
        configurationDeploy.setConfigurationId(configuration.getId());
        configurationDeploy.setFenceMode(FENCE_MODE_ALLOW_IN_AND_OUT);

        lmStub.configurationDeploy(configurationDeploy, lmAuth);
    }

    /**
     * Do the real work of launching the machine via SOAP.
     */
    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener taskListener)
            throws IOException, InterruptedException {
        final PrintStream logger = taskListener.getLogger();

        logger.println("Starting Virtual Machine...");
        /**
         * What we know is that at least at one point this particular
         * machine existed.  But we want to be sure it still exists.
         * If it exists we can check the status.  If we are off,
         * power on.  If we are suspended, resume.  If we are on,
         * do nothing.  The problem is that we don't have the machineId
         * right now so we need to call our getMachine.
         */
        LabManager labmanager = findOurLmInstance();
        Machine vm = getMachine(labmanager);

        int machineAction = MACHINE_ACTION_NONE;

        /* Determine the current state of the VM. */
        switch (vm.getStatus()) {
            case MACHINE_STATUS_UNDEPLOYED:
                machineAction = MACHINE_ACTION_DEPLOY;
                break;
            case MACHINE_STATUS_OFF:
                machineAction = MACHINE_ACTION_ON;
                break;
            case MACHINE_STATUS_SUSPENDED:
                machineAction = MACHINE_ACTION_RESUME;
                break;
            case MACHINE_STATUS_ON:
                /* Nothing to do */
                break;
            case MACHINE_STATUS_STUCK:
            case MACHINE_STATUS_INVALID:
                LOGGER.log(Level.SEVERE, "Problem with the machine status!");
                throw new IOException("Problem with the machine status");
        }

        /*
         * Perform check if configuration is deployed and then deploy
         * configuration
         */        
        if (machineAction == MACHINE_ACTION_DEPLOY) {
            Configuration configuration = getSingleConfiguration(labmanager);

            if (!configuration.getIsDeployed()) {
                // Deploying a configuration will automatically deploy all the VMs
                logger.printf("Deploy configuration '%s'\n", configuration.getName());
                deployConfiguration(labmanager, configuration);
                logger.printf("Configuration '%s' successfully deployed\n", configuration.getName());
            } else {
                logger.printf("Deploying virtual machine '%s' in configuration '%s'\n", vm.getName(), configuration.getName());
                performMachineAction(labmanager, vm, machineAction);
                logger.printf("Virtual machine '%s' successfully deployed\n", vm.getName());
            }
        } else {
            /*
             * Perform the action, if needed. This will be sleeping until it
             * returns from the server.
             */
            if (machineAction != MACHINE_ACTION_NONE) {
                performMachineAction(labmanager, vm, machineAction);
            }
        }

        // update VM information
        vm = getMachine(labmanager);

        try {
            /* At this point we have told Lab Manager to get the VM going.
             * Now we wait our launch delay amount before trying to connect. */
            Thread.sleep(launchDelay * 1000);

            final ComputerLauncher decoratedLauncherDelegateForMachine = 
                    getDecoratedLauncherDelegateForMachine(vm);
            decoratedLauncherDelegateForMachine.launch(slaveComputer, taskListener);
        } finally {
            /* If the rest of the launcher fails, we free up a space. */
            if (slaveComputer.getChannel() == null) {
                labmanager.markOneSlaveOffline(slaveComputer.getDisplayName());
            }
        }
    }

    /**
     * Handle bringing down the Virtual Machine.
     */
    @Override
    public void afterDisconnect(SlaveComputer slaveComputer,
            TaskListener taskListener) {
        taskListener.getLogger().println("Running disconnect procedure...");
        delegate.afterDisconnect(slaveComputer, taskListener);
        taskListener.getLogger().println("Shutting down Virtual Machine...");

        LabManager labmanager = findOurLmInstance();
        labmanager.markOneSlaveOffline(slaveComputer.getDisplayName());

        try {
            Machine vm = getMachine(labmanager);

            /* Determine the current state of the VM. */
            switch (vm.getStatus()) {
                case MACHINE_STATUS_OFF:
                case MACHINE_STATUS_SUSPENDED:
                    break;
                case MACHINE_STATUS_ON:
                    /* In the case where our idleAction is Suspend and Revert
                     * we need to first perform the shutdown and then the
                     * revert.  We will make the shutdown action and sleep for
                     * 60 seconds to try and make sure we have shutdown or at
                     * least that our JNLP connection has terminated.  In the
                     * case of Suspend or just Shutdown, we perform the action.
                     */
                    switch (idleAction) {
                        case MACHINE_ACTION_REVERT:
                            performMachineAction(labmanager, vm, MACHINE_ACTION_OFF);
                            taskListener.getLogger().println("Waiting 60 seconds for shutdown to complete.");
                            Thread.sleep(60000);
                        case MACHINE_ACTION_SUSPEND:
                        case MACHINE_ACTION_SHUTDOWN:
                        case MACHINE_ACTION_UNDEPLOY:
                            performMachineAction(labmanager, vm, idleAction);
                            break;
                    }
                    break;
                case MACHINE_STATUS_STUCK:
                case MACHINE_STATUS_INVALID:
                    LOGGER.log(Level.SEVERE, "Problem with the machine status!");
            }
        } catch (Throwable t) {
            taskListener.fatalError(t.getMessage(), t);
        }
    }

    public String getLmDescription() {
        return lmDescription;
    }

    public String getVmName() {
        return vmName;
    }

    public ComputerLauncher getDelegate() {
        return delegate;
    }

    public Boolean getOverrideLaunchSupported() {
        return overrideLaunchSupported;
    }

    public void setOverrideLaunchSupported(Boolean overrideLaunchSupported) {
        this.overrideLaunchSupported = overrideLaunchSupported;
    }

    @Override
    public boolean isLaunchSupported() {
        if (this.overrideLaunchSupported == null) {
            return delegate.isLaunchSupported();
        } else {
            LOGGER.log(Level.FINE, "Launch support is overridden to always return: " + overrideLaunchSupported);
            return overrideLaunchSupported;
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer slaveComputer, TaskListener taskListener) {
        delegate.beforeDisconnect(slaveComputer, taskListener);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        // Don't allow creation of launcher from UI
        throw new UnsupportedOperationException();
    }

    public int getIdleAction() {
        return idleAction;
    }

    public boolean isUpdateHostAddress() {
        return updateHostAddress;
    }

    public void setUpdateHostAddress(boolean updateHostAddress) {
        this.updateHostAddress = updateHostAddress;
    }

    /*
     * Create a new SSHLauncher when the IP address of the VM changed. This
     * usually happens if the VM was redeployed. Implementation note: I can't
     * create a "clean" decorator for the SSHLauncher, since SSHLauncher doesn't
     * have an interface. Therefore I'm creating a copy of the existing
     * SSHLauncher and copy all attributes to the new instance. The only
     * attribute untouched is the JdkInstaller, since this is not accessible
     * through SSHLaunchers public methods. Another way to prevent the creation
     * of the decorator, is to make the host attribute writable on the
     * SSHLauncher. The new SSHLauncher will not be persisted and you won't see
     * the current ip in the node configuration UI.
     */
    public ComputerLauncher getDecoratedLauncherDelegateForMachine(final Machine machine) {
        ComputerLauncher computerLauncher = getDelegate();

        if (computerLauncher instanceof SSHLauncher) {
            if (updateHostAddress) {
                SSHLauncher oldLauncher = (SSHLauncher) computerLauncher;

                if (!oldLauncher.getHost().equals(machine.getExternalIP())) {
                    LOGGER.log(Level.FINE, "Create a new SSHLauncher with new host information");

                    computerLauncher = new SSHLauncher(machine.getExternalIP(), 
                            oldLauncher.getPort(), 
                            oldLauncher.getUsername(),
                            oldLauncher.getPassword(), 
                            oldLauncher.getPrivatekey(), 
                            oldLauncher.getJvmOptions(),
                            oldLauncher.getJavaPath(), 
                            null /* JDK installer. potentially wrong! */, 
                            oldLauncher.getPrefixStartSlaveCmd(),
                            oldLauncher.getSuffixStartSlaveCmd());
                }
            }
        }

        return computerLauncher;
    }
}
