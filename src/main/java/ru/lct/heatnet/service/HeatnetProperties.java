package ru.lct.heatnet.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "heatnet")
public class HeatnetProperties {

    /** Root of on-disk storage: uploads, temporary files, results. */
    private String storageDir = "/data";
    private int runThreads = 2;
    private int importThreads = 2;
    private int queueCapacity = 200;
    private int copyBatchRows = 5000;
    /** Flow of the existing network when the input does not carry it: ZERO | CAPACITY_SHARE. */
    private String existingFlow = "ZERO";
    private double existingFlowShare = 0.5;

    public String getExistingFlow() {
        return existingFlow;
    }

    public void setExistingFlow(String existingFlow) {
        this.existingFlow = existingFlow;
    }

    public double getExistingFlowShare() {
        return existingFlowShare;
    }

    public void setExistingFlowShare(double existingFlowShare) {
        this.existingFlowShare = existingFlowShare;
    }
    /** Uploads and results are refused when less disk space than this is left, MB. */
    private long minFreeDiskMb = 2048;
    /** Finished runs kept per dataset; older ones are deleted with their files and tables. */
    private int keepRunsPerDataset = 50;
    /** Runs finished less than this many hours ago are never removed by the retention. */
    private int keepRunsMinHours = 24;

    public int getKeepRunsMinHours() {
        return keepRunsMinHours;
    }

    public void setKeepRunsMinHours(int keepRunsMinHours) {
        this.keepRunsMinHours = keepRunsMinHours;
    }

    public long getMinFreeDiskMb() {
        return minFreeDiskMb;
    }

    public void setMinFreeDiskMb(long minFreeDiskMb) {
        this.minFreeDiskMb = minFreeDiskMb;
    }

    public int getKeepRunsPerDataset() {
        return keepRunsPerDataset;
    }

    public void setKeepRunsPerDataset(int keepRunsPerDataset) {
        this.keepRunsPerDataset = keepRunsPerDataset;
    }

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public int getRunThreads() {
        return runThreads;
    }

    public void setRunThreads(int runThreads) {
        this.runThreads = runThreads;
    }

    public int getImportThreads() {
        return importThreads;
    }

    public void setImportThreads(int importThreads) {
        this.importThreads = importThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getCopyBatchRows() {
        return copyBatchRows;
    }

    public void setCopyBatchRows(int copyBatchRows) {
        this.copyBatchRows = copyBatchRows;
    }
}
