package com.ultimo;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.TimeZone;

import org.json.JSONObject;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.restheart.db.MongoDBClientSingleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.util.JSON;

public class ReportJob implements Job{
	
	private static final Logger LOGGER = LoggerFactory.getLogger("com.ultimo");
	
	
	public static List<DBObject> queryMongo(Date previousRun, String report, String jobName) throws Exception{
		
		MongoClient client = MongoDBClientSingleton.getInstance().getClient();

		BasicDBObject payload=(BasicDBObject)JSON.parse(report);
		payload = (BasicDBObject) payload.get("report");
		
		//since email and frequency are not needed for aggrigate query, remove frequency and email from the payloa.
		BasicDBObject frequency = (BasicDBObject)payload.removeField("frequency");
		payload.removeField("email");
		
		//BasicDBObject aggregateParam = ((BasicDBObject)payload.get("report"));
		
	    TimeZone tz = TimeZone.getTimeZone("UTC");
	    DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
	    df.setTimeZone(tz);
	    Date currentTime = new Date();
	    String currentJobExecutionTime = df.format(currentTime);
	    
	    
	    String previousJobExecutionTime;
	    
	    if(previousRun == null){
	    	int duationInSeconds = SettingService.calculateDurationInseconds(Integer.parseInt(frequency.getString("duration")), frequency.getString("unit"));
	    	long reportStartTime = currentTime.getTime()/1000 - duationInSeconds;
	    	
	    	previousJobExecutionTime = df.format(new Date(reportStartTime * 1000));
	    	
	    } else{
	    	previousJobExecutionTime = df.format(previousRun);
	    }
	    
	    if(!(payload.containsField("application") && payload.containsField("envid"))){
	    	LOGGER.error("The payload does not have the fields application and envid");
	    	IllegalArgumentException e = new IllegalArgumentException();
	    	throw e;
	    }
	    
	    if(payload.containsField("interface1") && payload.getString("interface1").length() == 0){
	    	payload.remove("interface1");
	    }
	    if(payload.containsField("errorType") && payload.getString("errorType").length() == 0){
	    	payload.remove("errorType");
	    }
	    
	    String matchParam = payload.toString();
	    
	    
	    
	    
	    
	    if(payload.containsField("interface1")){
	    	payload.replace("interface1", "$interface1");
	    }
	    else{
	    	 
	    	 payload.append("interface1", "$interface1");
	    }
	   
	    payload.remove("errorType");
	    payload.remove("application");
	    payload.remove("envid");
	    
	    String groupParam = payload.toString();
	    
		
		String query = "[{'$match':{'$and':[{'timestamp':{'$gte':{'$date':"+
        "'"+ previousJobExecutionTime +"'},'$lt':{'$date':'"+currentJobExecutionTime+"'}}},"+
        matchParam + "]}},{'$group':{'_id':" + groupParam + ",'count':{'$sum':1}}}]";
		
		
		
		LOGGER.info("Executing Job ("+ jobName + ") query " + query);
		
		//needs to get he colletion from config file
		String dbname = "ES";
		String collectionName = "ErrorSpotActual";
		
        DB database = client.getDB(dbname);
        DBCollection collection = database.getCollection(collectionName);
        List<DBObject> result = AggregateService.executeMongoAggregate(query, collection);
        LOGGER.info("the result: "+result.toString());

		return result;
	}

	@Override
	public void execute(JobExecutionContext context) throws JobExecutionException {
		
		JobDataMap jobData = context.getJobDetail().getJobDataMap();
		String jobName = context.getJobDetail().getKey().getName();
		Date previousJobExecutionTime = context.getPreviousFireTime();
		
		LOGGER.info("Report job with JobName " + jobName + " is started at " + context.getFireTime().toString()+ ". Report parameter is " + jobData.getString("report"));
		
		List<DBObject> result;
		try {
			result = queryMongo(previousJobExecutionTime, jobData.getString("report"), jobName);
			
			BasicDBObject resultObject = new BasicDBObject();
			BasicDBObject inputObject= (BasicDBObject) ((BasicDBObject)JSON.parse(jobData.getString("report"))).get("report");
			resultObject.put("Application", inputObject.get("application"));
			resultObject.put("Environment ID", inputObject.get("envid"));
			resultObject.put("Job Key", context.getJobDetail().getKey().toString());
			resultObject.put("Job ID", context.getFireInstanceId());
			resultObject.put("Execution Time", ""+context.getJobRunTime());
			resultObject.put("Time Ended", context.getFireTime().toString());
			Date previousFireTime=context.getPreviousFireTime();
			if(previousFireTime==null){
				Calendar calender= Calendar.getInstance();
				calender.setTime(context.getFireTime());
				calender.add(Calendar.MILLISECOND,(int)(context.getFireTime().getTime()-context.getNextFireTime().getTime()));
				previousFireTime=calender.getTime();			
			}
			resultObject.put("Time Started", previousFireTime.toString());
			resultObject.put("row", result);
			
			/*get email info from config file*/
			NotificationService.setEmailConfigs();
			String hostname=NotificationService.hostname;
			String port=NotificationService.port;
			String toEmailId=NotificationService.toEmailId;
			String fromEmailId=NotificationService.fromEmailId;
			String username=NotificationService.username;
			String password=NotificationService.password;
			String location=NotificationService.location;
			
			NotificationService.sendEmail(resultObject.toString(), location,inputObject.get("template").toString(), hostname, Integer.parseInt(port), toEmailId, fromEmailId, username, password);
			LOGGER.info("Executed report (" + jobName + ") output " + resultObject.toString());
		} catch (Exception e) {
			e.printStackTrace();
		}		
	}
}
