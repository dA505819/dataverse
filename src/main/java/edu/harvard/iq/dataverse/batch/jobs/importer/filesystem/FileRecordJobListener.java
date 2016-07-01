package edu.harvard.iq.dataverse.batch.jobs.importer.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.UserNotification;
import edu.harvard.iq.dataverse.UserNotificationServiceBean;
import edu.harvard.iq.dataverse.actionlogging.ActionLogRecord;
import edu.harvard.iq.dataverse.actionlogging.ActionLogServiceBean;
import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.batch.entities.JobExecutionEntity;
import org.apache.commons.io.FileUtils;

import javax.batch.api.BatchProperty;
import javax.batch.api.listener.JobListener;
import javax.batch.api.listener.StepListener;
import javax.batch.operations.JobOperator;
import javax.batch.runtime.BatchRuntime;
import javax.batch.runtime.BatchStatus;
import javax.batch.runtime.JobExecution;
import javax.batch.runtime.context.JobContext;
import javax.batch.runtime.context.StepContext;
import javax.ejb.EJB;
import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

@Named
@Dependent
public class FileRecordJobListener implements StepListener, JobListener {

    private static final Logger logger = Logger.getLogger(FileRecordJobListener.class.getName());

    @Inject
    private JobContext jobContext = null;

    @Inject
    private StepContext stepContext;

    @Inject
    @BatchProperty
    private String logDir;

    @EJB
    UserNotificationServiceBean notificationService;

    @EJB
    AuthenticationServiceBean authenticationServiceBean;

    @EJB
    ActionLogServiceBean actionLogServiceBean;

    @EJB
    DatasetServiceBean datasetService;

    @Override
    public void afterStep() throws Exception {
        System.out.println("FileRecordJobListener::afterStep");

    }

    @Override
    public void beforeStep() throws Exception {
        System.out.println("FileRecordJobListener::beforeStep");

    }

    @Override
    public void beforeJob() throws Exception {
        // no-op
    }

    @Override
    public void afterJob() throws Exception {

        doReport();
        logger.log(Level.INFO, "After Job {0}, instance {1} and execution {2}, batch status [{3}], exit status [{4}]",
                new Object[]{jobContext.getJobName(), jobContext.getInstanceId(), jobContext.getExecutionId(),
                        jobContext.getBatchStatus(), jobContext.getExitStatus()});
    }

    private void doReport() {

        try {

            // create job json
            String jobJson;
            JobExecution jobExecution = BatchRuntime.getJobOperator().getJobExecution(jobContext.getInstanceId());
            if (jobExecution != null) {
                JobExecutionEntity jobExecutionEntity = JobExecutionEntity.create(jobExecution);
                ObjectMapper mapper = new ObjectMapper();

                // job exit status and end time are null at the moment (we're still inside a job)
                // set the entity properties as if the job was complete
                jobExecutionEntity.setExitStatus("COMPLETED");
                jobExecutionEntity.setStatus(BatchStatus.COMPLETED);
                jobExecutionEntity.setEndTime(new Date());

                jobJson = mapper.writeValueAsString(jobExecutionEntity);

                logger.log(Level.INFO, "JSON: " + jobJson);

                // save json to log, send notification & create action log entry
                saveJsonLog(jobJson);
                JobOperator jobOperator = BatchRuntime.getJobOperator();
                Properties jobParams = jobOperator.getParameters(jobContext.getInstanceId());
                sendNotification(jobParams.getProperty("userId"), jobParams.getProperty("datasetId"));
                createActionLogRecord(jobParams.getProperty("userId"), jobExecution, jobJson);

            } else {
                logger.log(Level.SEVERE, "Job execution is null");
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error creating job json: " + e.getMessage());
        }

    }

    private void saveJsonLog(String jobJson) {

        try {
            File dir = new File(logDir);
            if (!dir.exists() && !dir.mkdirs()) {
                logger.log(Level.SEVERE, "Couldn't create directory: " + dir.getAbsolutePath());
            }
            File reportJson = new File(dir.getAbsolutePath() + "/job-" + jobContext.getInstanceId() + ".json");
            FileUtils.writeStringToFile(reportJson, jobJson);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error saving json report: " + e.getMessage());
        }

    }

    private void sendNotification(String userId, String datasetId) {

        AuthenticatedUser user = authenticationServiceBean.getAuthenticatedUser(userId);
        if (user == null) {
            logger.log(Level.SEVERE, "Cannot find authenticated user with ID: " + userId);
        }

        Dataset dataset = datasetService.findByGlobalId(datasetId);
        if (dataset == null) {
            logger.log(Level.SEVERE, "Cannot find dataset with ID: " + datasetId);
        }

        try {
            notificationService.sendNotification(
                    user,
                    new Timestamp(new Date().getTime()),
                    UserNotification.Type.FILESYSTEMIMPORT,
                    dataset.getLatestVersion().getId());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error sending job notification: " + e.getMessage());
        }

    }

    private void createActionLogRecord(String userId, JobExecution execution, String log) {

        try {
            ActionLogRecord alr = new ActionLogRecord(ActionLogRecord.ActionType.Command, execution.getJobName());
            alr.setId(Long.toString(jobContext.getInstanceId()));
            alr.setInfo(log);
            alr.setUserIdentifier(userId);
            alr.setStartTime(execution.getStartTime());
            alr.setEndTime(execution.getEndTime());
            if (execution.getBatchStatus().name().equalsIgnoreCase("COMPLETED")) {
                alr.setActionResult(ActionLogRecord.Result.OK);
            } else {
                alr.setActionResult(ActionLogRecord.Result.InternalError);
            }
            actionLogServiceBean.log(alr);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error creating action log record: " + e.getMessage());
        }
    }

}
