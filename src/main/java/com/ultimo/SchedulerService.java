package com.ultimo;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.restheart.db.MongoDBClientSingleton;
import org.restheart.handlers.PipedHttpHandler;
import org.restheart.handlers.RequestContext;
import org.restheart.handlers.RequestContext.METHOD;
import org.restheart.handlers.applicationlogic.ApplicationLogicHandler;
import org.restheart.security.handlers.IAuthToken;
import org.restheart.utils.HttpStatus;
import org.restheart.utils.ResponseHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.MongoClient;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;

public class SchedulerService extends ApplicationLogicHandler implements IAuthToken{
	
	public SchedulerService(PipedHttpHandler next, Map<String, Object> args) {
		super(next, args);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("com.ultimo");
	
	public static Scheduler scheduler;
	
	public static void startScheduler() throws SchedulerException{
		startScheduler("");
	}
	
	public static void startScheduler(String propertiesFile) throws SchedulerException{
		try {
			LOGGER.info("attempting to start scheduler");
			//get scheduler
			StdSchedulerFactory schedulerFactory = new StdSchedulerFactory();
			if(propertiesFile.length()>0){
				//check existence of file
				File file = new File(propertiesFile);
				if(file.exists()){
					schedulerFactory.initialize(propertiesFile);
				}
				else{
					LOGGER.warn(propertiesFile+" doesn't exist in current working directory");
				}
			}
			else{
				schedulerFactory.initialize("etc/quartz.properties");
			}
			scheduler = schedulerFactory.getScheduler();
			scheduler.start();
			
			//get and schedule existing reports
			LOGGER.info("Scheduler "+scheduler.getSchedulerName()+ "started, getting existing report and scheduling them");
			DBCursor cursor=getReports();
			LOGGER.info("Scheduling "+cursor.size()+" existing reports");
			while(cursor.hasNext()){
				//schedule the existing reports
				scheduleReport(new JSONObject(cursor.next().toString()));
			}
		} catch (SchedulerException e) {
			LOGGER.error(e.getMessage());
			LOGGER.error("the error: ",e);
			throw e;
		} 
		//shouldn't ever be in these catch blocks
		catch (JSONException e) {
			LOGGER.error(e.getMessage());
			LOGGER.error("the error: ",e);
		} 
		catch (ParseException e) {
			LOGGER.error(e.getMessage());
			LOGGER.error("the error: ",e);
		}
	}
	
	public static void stopScheduler() throws SchedulerException{
		if(scheduler==null || !scheduler.isStarted()){
			LOGGER.info("the scheduler hasn't been started yet");
			return;
		}
		else{
			LOGGER.info("shutting down the scheduler "+scheduler.getSchedulerName());
			scheduler.shutdown();
		}
	}
	
	public static Date scheduleReport(JSONObject report) throws SchedulerException, java.text.ParseException{		
		LOGGER.info("scheduling passed report");
		LOGGER.trace("report: "+report.toString());
		String jobKeyName = getJobName(report);
		LOGGER.trace("jobKey= "+jobKeyName);
		if(scheduler==null || !scheduler.isStarted()){
			LOGGER.error("the scheduler is not started, so the job: "+jobKeyName+" will not be scheduled");
			return null;
		}
		JobKey jobKey = new JobKey(jobKeyName);
		JobDetail job;
		try{
				job = scheduler.getJobDetail(jobKey);
				
				//remove old job if exists
				scheduler.deleteJob(jobKey);
				
		} catch(SchedulerException e){
			LOGGER.info("Job with JobKey " + jobKeyName + " does not exits. Createing new Job");

		}
		
		//create  job
		job = JobBuilder.newJob(ReportJob.class)
				.withIdentity(jobKey).build();

		
		job.getJobDataMap().put("report", report.toString());
		
		LOGGER.info("created new job with job key: "+jobKeyName);
		
		//Create Trigger
		Trigger trigger = getSechduleTrigger(report, jobKeyName);
		
		LOGGER.info("created new trigger");
		Date startDateTime = scheduler.scheduleJob(job, trigger);
		LOGGER.info("Report " + jobKeyName + " is scheduled to start at " + startDateTime.toString() + " and will run every " 
		+ report.getJSONObject("report").getJSONObject("frequency").getString("duration") + " " 
				+ report.getJSONObject("report").getJSONObject("frequency").getString("unit"));
		
		return startDateTime;
		
	}

	private static Trigger getSechduleTrigger(JSONObject report, String triggerName) throws JSONException, java.text.ParseException {
		LOGGER.trace("trigger name: "+triggerName);
		//JSONObject report = new JSONObject(payload);
		
		JSONObject frequency; 
		int duration; 
		String unit;
		try{
			frequency = report.getJSONObject("report").getJSONObject("frequency");
			duration = frequency.getInt("duration");
			unit = frequency.getString("unit");
		}
		catch (JSONException e){
			LOGGER.error(e.getMessage());
			LOGGER.error("the error: ",e);
			throw e;
		}
		Date triggerStartTime;
		String startDateTime = frequency.getString("starttime");
		

		if (startDateTime != null && !startDateTime.isEmpty()) {
			LOGGER.info("start time is given");

			SimpleDateFormat formatter = new SimpleDateFormat(
					"MM/dd/yyyy'T'hh:mm:ss");
			triggerStartTime = formatter.parse(startDateTime);

		} else {
			triggerStartTime = new Date();
			LOGGER.info("no starttime is given, using current time as default stattime");
		}

		// default schedule is 1 hr
		int seconds = calculateDurationInseconds(duration, unit);

		LOGGER.trace("seconds: "+seconds);

		SimpleScheduleBuilder scheduleBuilder = SimpleScheduleBuilder
				.simpleSchedule().withIntervalInSeconds(seconds)
				.repeatForever();

		Trigger trigger = TriggerBuilder.newTrigger()
				.withIdentity(triggerName).withSchedule(scheduleBuilder)
				.startAt(triggerStartTime).build();

		return trigger;
	}
	
	public static int calculateDurationInseconds(int duration, String unit){
		LOGGER.info("calculating duration in seconds");
		LOGGER.trace("duration: "+duration);
		LOGGER.trace("unit: "+ unit);
		
		//default is 1 hr
		int seconds = 60 * 60;

		switch (unit) {

		case "sec":
			seconds = duration;
			break;
		case "min":
			seconds = 60 * duration;
			break;
		case "hr":
			seconds = 60 * 60 * duration;
			break;
		case "hrs":
			seconds = 60 * 60 * duration;
			break;
		case "day":
			seconds = 24 * 60 * 60 * duration;
			break;
		case "days":
			seconds = 24 * 60 * 60 * duration;
			break;

		default:
			
		}
		LOGGER.info("the time interval is scheduled for "+seconds+" seconds");
		return seconds;
	} 

	public static String getJobName(JSONObject report){
		LOGGER.info("getting the name of the job");
		LOGGER.trace("getting the job name for the job: "+report.toString());
	
		String envName = report.getJSONObject("report").getString("envid");
		
		String applicationName = report.getJSONObject("report").getString("application");
		
		String jobKeyName=envName+"."+applicationName;
		
		//Interface and error type are optional fields
		try{
			String interfaceName = report.getJSONObject("report").getString("interface1");
			
			if(!interfaceName.isEmpty())
				jobKeyName = jobKeyName + "." + interfaceName;
			
		} catch (JSONException e){
			LOGGER.warn(e.getMessage());
		}
		
		try{
			
			String errorType = report.getJSONObject("report").getString("errorType");
			
			if(!errorType.isEmpty())
				jobKeyName = jobKeyName + "." + errorType;
			
		} catch (JSONException e){
			LOGGER.warn(e.getMessage());
			
		}
		
		LOGGER.info("Job Key name " + jobKeyName);
		
		return jobKeyName;
	}
	
	public static boolean deleteJob(JSONObject report) throws Exception{
		LOGGER.info("deleting job associated with the passed report");
		LOGGER.info("reprot: "+report);
		if(scheduler==null || !scheduler.isStarted()){
			//don't delete if scheduler is not started
			LOGGER.info("The scheduler is not started, so there is no job to delete");
			return false;
		}
		boolean jobDeleted=false;
		try{
			String jobKeyName=getJobName(report);
			LOGGER.info("deleting Job with JobKey: "+jobKeyName);
			JobKey jobKey = new JobKey(jobKeyName);
			jobDeleted=scheduler.deleteJob(jobKey);
				
		} catch(SchedulerException e){
			LOGGER.info("Job with JobKey " + getJobName(report) + " does not exits. so no job deleted");

		}
		return jobDeleted;
	}

	public static DBCursor getReports(){
		LOGGER.info("getting exiting reports from the datbase");
		MongoClient client = MongoDBClientSingleton.getInstance().getClient();
		String dbname = MongoDBClientSingleton.getErrorSpotConfig("u-mongodb-database");
		String collectionName = MongoDBClientSingleton.getErrorSpotConfig("u-setting-collection");
        DB database = client.getDB(dbname);
        DBCollection collection = database.getCollection(collectionName);
        LOGGER.info("querring all the reports in db: "+dbname+" and collection: "+collectionName);  
        //this gets report documents only
        BasicDBObject whereQuery = new BasicDBObject();
		whereQuery.put("report", new BasicDBObject("$ne", null));
		LOGGER.trace("searching for document that match the fgiven qualification: "+whereQuery.toString());
		DBCursor cursor = collection.find(whereQuery);
		return cursor;
	}

	@Override
	public void handleRequest(HttpServerExchange exchange, RequestContext context) throws Exception {
		LOGGER.info("starting the SchedulerService");
		if (context.getMethod() == METHOD.OPTIONS) {
			ErrorSpotSinglton.optionsMethod(exchange);
        }
		else if(context.getMethod() == METHOD.POST){
			LOGGER.info("Starting the post in SchedulerService");
			Map<String, Deque<String>> queryParams= exchange.getQueryParameters();
			LOGGER.trace("Query Parameters: "+queryParams.toString());
			if(!queryParams.containsKey("server")){
				LOGGER.error("No server field was specified. Server Status must be specified preceded by: \"server= \"");
				ResponseHelper.endExchangeWithMessage(exchange, HttpStatus.SC_BAD_REQUEST, "Server Status must be specified preceded by: \"server= \"");
				return;
			}
			if(queryParams.get("server").size()!=1){
				LOGGER.error("Multiple calls to server are being specified. only one server must be specified preceded by: \"server= \"");
				ResponseHelper.endExchangeWithMessage(exchange, HttpStatus.SC_BAD_REQUEST, "Multiple calls to server are being specified. only one server must be specified.");
				return;
			}
			String serverRequest=queryParams.get("server").getFirst();
			LOGGER.trace("the server request: "+serverRequest);
			Deque<String> propertiesFile=queryParams.get("propertiesFile");
			if(serverRequest.equals("start")){
				LOGGER.info("strating scheduler");
				if(queryParams.get("propertiesFile")!=null){
					LOGGER.info("starting scheduler with properties file: "+propertiesFile.getFirst());
					File file =new File(propertiesFile.getFirst());
					if(!file.exists()){
						LOGGER.error("The given properties file does not exist");
						ResponseHelper.endExchangeWithMessage(exchange, HttpStatus.SC_NOT_FOUND, "The given properties file does not exist");
						return;
					}
					startScheduler(propertiesFile.getFirst());
				}
				else{
					startScheduler();
				}
				LOGGER.info("successfully started scheudler "+scheduler.getSchedulerName());
				exchange.getResponseSender().send("Started scheduler: "+scheduler.getSchedulerName());
			}
			else if(serverRequest.equals("stop")){
				LOGGER.info("stopping scheudler");
				stopScheduler();
			}
			else{
				LOGGER.error("server request can only be start or stop and passed in value is: "+serverRequest);
				ResponseHelper.endExchangeWithMessage(exchange, HttpStatus.SC_BAD_REQUEST, "the passed server request: "+serverRequest+" is invalid. It can be only start or stop");
			}
		}
		else 
        {
			LOGGER.info("invaild http option");
        	ResponseHelper.endExchangeWithMessage(exchange, HttpStatus.SC_METHOD_NOT_ALLOWED, "Method Not Allowed. Post Only ");
        }
	}
}
