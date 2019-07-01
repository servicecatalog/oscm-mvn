package org.oscm.app.vmware.usage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.oscm.app.v2_0.exceptions.APPlatformException;
import org.oscm.app.vmware.business.VMPropertyHandler;
import org.oscm.app.vmware.remote.vmware.VMClientPool;
import org.oscm.app.vmware.remote.vmware.VMwareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.PerfCounterInfo;
import com.vmware.vim25.PerfEntityMetricBase;
import com.vmware.vim25.PerfEntityMetricCSV;
import com.vmware.vim25.PerfMetricId;
import com.vmware.vim25.PerfMetricSeriesCSV;
import com.vmware.vim25.PerfQuerySpec;
import com.vmware.vim25.RuntimeFaultFaultMsg;

public class VMMetricCollector {

    static final String CPU_USAGE_PERCENT = "cpu.usagemhz.AVERAGE";
    static final String MEM_USAGE_PERCENT = "mem.usage.AVERAGE";
    static final String DISK_USAGE_TOTAL = "disk.used.LATEST";

    static final int INTERVALL_ONE_DAY = 300;
    static final int INTERVALL_ONE_WEEK = 1800;
    static final int INTERVALL_ONE_MONTH = 7200;
    static final int INTERVALL_ONE_YEAR = 86400;

    HashMap<String, Integer> countersIdMap = new HashMap<String, Integer>();
    HashMap<Integer, PerfCounterInfo> countersInfoMap = new HashMap<Integer, PerfCounterInfo>();

    private static final Logger LOGGER = LoggerFactory
            .getLogger(VMMetricCollector.class);

    VMPropertyHandler ph;
    ManagedObjectReference vmInstance;
    ManagedObjectReference performanceManager;
    VMwareClient vmw;

    VMMetricCollector(VMPropertyHandler ph) {
        this.ph = ph;
    }

    public void initialize() {

        String vcenter = ph
                .getServiceSetting(VMPropertyHandler.TS_TARGET_VCENTER_SERVER);
        try {
            vmw = VMClientPool.getInstance().getPool().borrowObject(vcenter);
            vmInstance = vmw.getServiceUtil().getDecendentMoRef(null,
                    "VirtualMachine", ph.getInstanceName());

            performanceManager = vmw.getConnection().getServiceContent()
                    .getPerfManager();

            List<PerfCounterInfo> perfCounters = (List<PerfCounterInfo>) vmw
                    .getServiceUtil()
                    .getDynamicProperty(performanceManager, "perfCounter");

            createCounterToNameMapping(perfCounters);

        } catch (Exception e) {
            LOGGER.error("Can´t gather usage data for instance "
                    + ph.getInstanceId() + "\n" + e.getMessage());
        }
    } 

    public ArrayList<String> createMetricResult(String name)
            throws APPlatformException, RuntimeFaultFaultMsg {
        List<PerfMetricId> perfMetricIds = createMetrics(name);
        List<PerfQuerySpec> pqsList = createPerfQuerySpec(vmInstance,
                perfMetricIds);

        List<PerfEntityMetricBase> retrievedStats = vmw.getService().queryPerf(performanceManager, pqsList);

        return createResultFromStats(retrievedStats);
    }

    protected List<PerfQuerySpec> createPerfQuerySpec(
            ManagedObjectReference vmInstance,
            List<PerfMetricId> perfMetricIds) {
        List<PerfQuerySpec> pqsList = new ArrayList<PerfQuerySpec>();
        PerfQuerySpec querySpecification = new PerfQuerySpec();
        querySpecification.setEntity(vmInstance);
        querySpecification.setIntervalId(INTERVALL_ONE_YEAR);
        querySpecification.setFormat("csv");
        querySpecification.getMetricId().addAll(perfMetricIds);
        pqsList.add(querySpecification);
        return pqsList;
    }

    protected List<PerfMetricId> createMetrics(String counterName) {
        List<PerfMetricId> perfMetricIds = new ArrayList<PerfMetricId>();
        PerfMetricId metricId = new PerfMetricId();
        metricId.setCounterId(countersIdMap.get(counterName));
        String instanceType = "*";
        metricId.setInstance(instanceType); // here a wildcard is used to get
                                          // every Instance
        perfMetricIds.add(metricId);
        return perfMetricIds;
    }

    protected ArrayList<String> createResultFromStats(
            List<PerfEntityMetricBase> retrievedStats)
            throws APPlatformException {
        ArrayList<String> result = new ArrayList<String>();
        for (PerfEntityMetricBase singleEntityPerfStats : retrievedStats) {

            PerfEntityMetricCSV entityStatsCsv = (PerfEntityMetricCSV) singleEntityPerfStats;
            List<PerfMetricSeriesCSV> metricsValues = entityStatsCsv.getValue();

            if (metricsValues.isEmpty()) {
                LOGGER.error("No stats retrieved. "
                        + "Check whether the virtual machine is powered on.");
                throw new APPlatformException("No stats retrieved. "
                        + "Check whether the virtual machine is powered on.");
            }
            result = getResults(metricsValues);
        }
        return result;
    }

    protected ArrayList<String> getResults(
            List<PerfMetricSeriesCSV> metricsValues) {
        ArrayList<String> result = new ArrayList<String>();
        for (PerfMetricSeriesCSV csv : metricsValues) {
            if (csv.getId().getInstance().isEmpty()) {
            LOGGER.info("Instance: " + csv.getId().getInstance());
            LOGGER.info("Values: " + csv.getValue());
                result.add(csv.getValue());
            }
        }
        return result;
    }

    // counter Id's can be different in every vSphere environment, therefore
    // it,s necessary to map the id's to names
    protected void createCounterToNameMapping(
            List<PerfCounterInfo> perfCounters) {
        for (PerfCounterInfo perfCounter : perfCounters) {
            Integer counterId = new Integer(perfCounter.getKey());
            countersInfoMap.put(counterId, perfCounter);
            String counterGroup = perfCounter.getGroupInfo().getKey();
            String counterName = perfCounter.getNameInfo().getKey();
            String counterRollupType = perfCounter.getRollupType().toString();
            String fullCounterName = counterGroup + "." + counterName + "."
                    + counterRollupType;
            LOGGER.info(
                    counterGroup + "." + counterName + "." + counterRollupType);
            countersIdMap.put(fullCounterName, counterId);
        }
    }
    
    protected String resultListToString(List resultList) {
        String result ="";
        for(int i = 0; i<resultList.size(); i++) {
            result = result + resultList.get(i);
        }
        return result;
    }
    
    protected String getLastDayValue(String values) {
        String[] value = values.split(",");
        String lastDayValue ="";
        if(value.length > 0) {
            lastDayValue = value[value.length -1];
        }
        return lastDayValue;
    }

    public long getDiskUsageTotalKB()
            throws APPlatformException, RuntimeFaultFaultMsg {
        ArrayList<String> result = new ArrayList<String>();
        result = createMetricResult(DISK_USAGE_TOTAL);
        String lastDayValue = getLastDayValue(resultListToString(result));
        long memUsageBK = Integer.parseInt(lastDayValue);
        return memUsageBK;
    }
    
    public long getMemUsagePercent()
            throws APPlatformException, RuntimeFaultFaultMsg {
        ArrayList<String> result = new ArrayList<String>();
        result = createMetricResult(MEM_USAGE_PERCENT);
        String lastDayValue = getLastDayValue(resultListToString(result));
        long memUsage = Integer.parseInt(lastDayValue);
        long memUsagePercent = memUsage / 100; //number is given with 2 decimal places without separating point
        return memUsagePercent;
    }

    public int getCpuUsageMhz()
            throws APPlatformException, RuntimeFaultFaultMsg {
        ArrayList<String> result = new ArrayList<String>();
        result = createMetricResult(CPU_USAGE_PERCENT);
        return Integer.parseInt(getLastDayValue(resultListToString(result)));
    }

}
