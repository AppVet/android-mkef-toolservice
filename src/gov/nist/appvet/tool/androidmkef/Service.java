/* This software was developed by employees of the National Institute of
 * Standards and Technology (NIST), an agency of the Federal Government.
 * Pursuant to title 15 United States Code Section 105, works of NIST
 * employees are not subject to copyright protection in the United States
 * and are considered to be in the public domain.  As a result, a formal
 * license is not needed to use the software.
 * 
 * This software is provided by NIST as a service and is expressly
 * provided "AS IS".  NIST MAKES NO WARRANTY OF ANY KIND, EXPRESS, IMPLIED
 * OR STATUTORY, INCLUDING, WITHOUT LIMITATION, THE IMPLIED WARRANTY OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, NON-INFRINGEMENT
 * AND DATA ACCURACY.  NIST does not warrant or make any representations
 * regarding the use of the software or the results thereof including, but
 * not limited to, the correctness, accuracy, reliability or usefulness of
 * the software.
 * 
 * Permission to use this software is contingent upon your acceptance
 * of the terms of this agreement.
 */
package gov.nist.appvet.tool.androidmkef;

import gov.nist.appvet.tool.androidmkef.util.FileUtil;
import gov.nist.appvet.tool.androidmkef.util.HttpUtil;
import gov.nist.appvet.tool.androidmkef.util.Logger;
import gov.nist.appvet.tool.androidmkef.util.Protocol;
import gov.nist.appvet.tool.androidmkef.util.ReportFormat;
import gov.nist.appvet.tool.androidmkef.util.ReportUtil;
import gov.nist.appvet.tool.androidmkef.util.ToolStatus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

/**
 * This class implements a synchronous tool service.
 */
public class Service extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final String reportName = "report";
	private static final Logger log = Properties.log;
	private static String appDirPath = null;
	private String appFilePath = null;
	private String reportFilePath = null;
	private String outputFilePath = null;
	private String fileName = null;
	private String appId = null;
	private String command = null;
	private StringBuffer reportBuffer = null;

	/** CHANGE (START): Add expected HTTP request parameters **/
	/** CHANGE (END): Add expected HTTP request parameters **/
	public Service() {
		super();
	}

	/*// AppVet tool services will rarely use HTTP GET
	 * protected void doGet(HttpServletRequest request, HttpServletResponse
	 * response) throws ServletException, IOException {
	 */

	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		// Get received HTTP parameters and file upload
		FileItemFactory factory = new DiskFileItemFactory();
		ServletFileUpload upload = new ServletFileUpload(factory);
		List items = null;
		FileItem fileItem = null;

		try {
			items = upload.parseRequest(request);
		} catch (FileUploadException e) {
			e.printStackTrace();
		}

		// Get received items
		Iterator iter = items.iterator();
		FileItem item = null;

		while (iter.hasNext()) {
			item = (FileItem) iter.next();
			if (item.isFormField()) {
				// Get HTML form parameters
				String incomingParameter = item.getFieldName();
				String incomingValue = item.getString();
				if (incomingParameter.equals("appid")) {
					appId = incomingValue;
				}
				/** CHANGE (START): Get other tools-specific form parameters **/
				/** CHANGE (END): Get other tools-specific form parameters **/
			} else {
				// item should now hold the received file
				if (item != null) {
					fileItem = item;
					log.debug("Received file: " + fileItem.getName());
				}
			}
		}

		if (appId == null) {
			// All tool services require an AppVet app ID
			HttpUtil.sendHttp400(response, "No app ID specified");
			return;
		}

		if (fileItem != null) {
			// Get app file
			fileName = FileUtil.getFileName(fileItem.getName());
			if (!fileName.endsWith(".apk")) {
				HttpUtil.sendHttp400(response,
						"Invalid app file: " + fileItem.getName());
				return;
			}
			
			// Create app directory
			appDirPath = Properties.TEMP_DIR + "/" + appId;
			File appDir = new File(appDirPath);
			if (!appDir.exists()) {
				appDir.mkdir();
			}
			
			// Create report path
			reportFilePath = Properties.TEMP_DIR + "/" + appId + "/"
					+ reportName + "." + Properties.reportFormat.toLowerCase();
			appFilePath = Properties.TEMP_DIR + "/" + appId + "/" + fileName;

			if (!FileUtil.saveFileUpload(fileItem, appFilePath)) {
				HttpUtil.sendHttp500(response, "Could not save uploaded file");
				return;
			}
		} else {
			HttpUtil.sendHttp400(response, "No app was received.");
			return;
		}

		// Use if reading command from ToolProperties.xml. Otherwise, 
		// comment-out if using custom command (called by customExecute())
		//command = getCommand();
		reportBuffer = new StringBuffer();

		// If asynchronous, send acknowledgement back to AppVet so AppVet
		// won't block waiting for a response.
		if (Properties.protocol.equals(Protocol.ASYNCHRONOUS.name())) {
			HttpUtil.sendHttp202(response, "Received app " + appId
					+ " for processing.");
		}
		
		/* CHANGE: Select either execute() to execute a native OS command
		 * or customExecute() to execute your own custom code. Make sure that
		 * the unused method call is commented-out.
		 */
		//boolean succeeded = execute(command, reportBuffer);
		boolean succeeded = customExecute(reportBuffer);
		
		if (!succeeded) {
			log.error("Error detected: " + reportBuffer.toString());
			String errorReport = ReportUtil.getHtmlReport(response, fileName, ToolStatus.ERROR,
					reportBuffer.toString(),
					"Description: \tApp does not contain Android MasterKey or ExtraField vulnerabilities.\n\n",
					null,
					"Description: \tApp contains Android MasterKey and/or ExtraField vulnerabilities.\n\n",
					"Description: \tError or exception processing app.\n\n");
			// Send report to AppVet
			if (Properties.protocol.equals(Protocol.SYNCHRONOUS.name())) {
				// Send back ASCII in HTTP Response
				ReportUtil.sendInHttpResponse(response, errorReport, ToolStatus.ERROR);
			} else if (Properties.protocol.equals(Protocol.ASYNCHRONOUS.name())) {
				// Send report file in new HTTP Request to AppVet
				if (FileUtil.saveReport(errorReport, reportFilePath)) {
					ReportUtil.sendInNewHttpRequest(appId, reportFilePath, ToolStatus.ERROR);
				}
			}
			return;
		}

		// Analyze report and generate tool status
		log.debug("Analyzing report for " + appFilePath);
		ToolStatus reportStatus = 
				ReportUtil.analyzeReport(reportBuffer.toString());
		log.debug("Result: " + reportStatus.name());
		String reportContent = null;

		// Get report
		if (Properties.reportFormat.equals(ReportFormat.HTML.name())) {
			reportContent = ReportUtil.getHtmlReport(response, fileName, reportStatus,
					reportBuffer.toString(),
					"Description: \tApp does not contain Android MasterKey or ExtraField vulnerabilities.\n\n",
					null,
					"Description: \tApp contains Android MasterKey and/or ExtraField vulnerabilities.\n\n",
					"Description: \tError or exception processing app.\n\n");
		} else if (Properties.reportFormat.equals(ReportFormat.TXT.name())) {
			reportContent = getTxtReport();
		} else if (Properties.reportFormat.equals(ReportFormat.PDF.name())) {
			reportContent = getPdfReport();
		} else if (Properties.reportFormat.equals(ReportFormat.JSON.name())) {
			reportContent = getJsonReport();
		}

		// If report content is null or empty, stop processing
		if (reportContent == null || reportContent.isEmpty()) {
			log.error("Tool report is null or empty");
			return;
		}

		// Send report to AppVet
		if (Properties.protocol.equals(Protocol.SYNCHRONOUS.name())) {
			// Send back ASCII in HTTP Response
			ReportUtil.sendInHttpResponse(response, reportContent, reportStatus);
		} else if (Properties.protocol.equals(Protocol.ASYNCHRONOUS.name())) {
			// Send report file in new HTTP Request to AppVet
			if (FileUtil.saveReport(reportContent, reportFilePath)) {
				ReportUtil.sendInNewHttpRequest(appId, reportFilePath, reportStatus);
			}
		}

		// Clean up
		if (FileUtil.deleteDirectory(new File(appDirPath))) {
			log.debug("Deleted " + appFilePath);
		} else {
			log.warn("Could not delete " + appFilePath);
		}
		
		reportBuffer = null;
		System.gc();
	}

	public String getCommand() {
		// Get command from ToolProperties.xml file
		String cmd1 = Properties.command;
		String cmd2 = null;
		String cmd3 = null;
		if (cmd1.indexOf(Properties.APP_FILE_PATH) > -1) {
			// Add app file path
			cmd2 = cmd1.replace(Properties.APP_FILE_PATH, appFilePath);
		} else {
			cmd2 = cmd1;
		}
		if (cmd2.indexOf(Properties.OUTPUT_FILE_PATH) > -1) {
			// Add output (e.g., report) file path
			cmd3 = cmd2.replace(Properties.OUTPUT_FILE_PATH, outputFilePath);
		} else {
			cmd3 = cmd2;
		}
		log.debug("full command: " + cmd3);
		return cmd3;
	}

/*	private static boolean execute(String command, StringBuffer output) {
		List<String> commandArgs = Arrays.asList(command.split("\\s+"));
		ProcessBuilder pb = new ProcessBuilder(commandArgs);
		Process process = null;
		IOThreadHandler outputHandler = null;
		IOThreadHandler errorHandler = null;
		int exitValue = -1;
		try {
			log.debug("Executing " + command);
			process = pb.start();
			outputHandler = new IOThreadHandler(process.getInputStream());
			outputHandler.start();
			errorHandler = new IOThreadHandler(process.getErrorStream());
			errorHandler.start();
			if (process.waitFor(Properties.commandTimeout,
					TimeUnit.MILLISECONDS)) {
				// Process has waited and exited within the timeout
				exitValue = process.exitValue();
				if (exitValue == 0) {
					StringBuilder resultOut = outputHandler.getOutput();
					output.append(resultOut);
				} else {
					StringBuilder resultError = errorHandler.getOutput();
					output.append(resultError);
				}
				return true;
			} else {
				// Process exceed timeout or was interrupted
				StringBuilder resultOutput = outputHandler.getOutput();
				StringBuilder resultError = errorHandler.getOutput();
				if (resultOutput != null) {
					output.append(resultOutput);
					return false;
				} else if (resultError != null) {
					output.append(resultError);
				} else {
					output.append(Properties.toolName + " timed-out");
				}
				return false;
			}
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		} catch (InterruptedException e) {
			e.printStackTrace();
			return false;
		} finally {
			if (outputHandler.isAlive()) {
				try {
					outputHandler.inputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (errorHandler.isAlive()) {
				try {
					errorHandler.inputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (process.isAlive()) {
				process.destroy();
			} 
		}

	}

	private static class IOThreadHandler extends Thread {
		private InputStream inputStream;
		private StringBuilder output = new StringBuilder();
		private static final String lineSeparator = 
				System.getProperty("line.separator");;

		IOThreadHandler(InputStream inputStream) {
			this.inputStream = inputStream;
		}

		public void run() {
			Scanner br = null;
			try {
				br = new Scanner(new InputStreamReader(inputStream));
				String line = null;
				while (br.hasNextLine()) {
					line = br.nextLine();
					output.append(line + lineSeparator);
				}
			} finally {
				br.close();
			}
		}

		public StringBuilder getOutput() {
			return output;
		}
	}*/
	
	public boolean customExecute(StringBuffer output) {
		MKEFScanner mkefScan = new MKEFScanner(appFilePath);
		if (mkefScan.hasMasterKey()) {
			// The following String MUST match in ToolProperties.xml
			output.append("Android MasterKey vulnerability detected.");
			return true;
		} else if (mkefScan.hasExtraField()) {
			// The following String MUST match in ToolProperties.xml
			output.append("Android ExtraField vulnerability detected.");
			return true;
		} else {
			output.append("No Android MaskterKey or ExtraField vulnerabilities detected.");
		}
		return true;
	}



	// TODO
	public String getTxtReport() {
		return null;
	}

	// TODO
	public String getPdfReport() {
		return null;
	}

	// TODO
	public String getJsonReport() {
		return null;
	}




//	/** This method should be used for sending files back to AppVet. */
//	public boolean sendInNewHttpRequest(String reportFilepath,
//			ToolStatus reportStatus) {
//		HttpParams httpParameters = new BasicHttpParams();
//		HttpConnectionParams.setConnectionTimeout(httpParameters, 30000);
//		HttpConnectionParams.setSoTimeout(httpParameters, 1200000);
//		HttpClient httpClient = new DefaultHttpClient(httpParameters);
//		httpClient = SSLWrapper.wrapClient(httpClient);
//
//		try {
//			/*
//			 * To send reports back to AppVet, the following parameters must be
//			 * sent: - command: SUBMIT_REPORT - username: AppVet username -
//			 * password: AppVet password - appid: The app ID - toolid: The ID of
//			 * this tool - toolrisk: The risk assessment
//			 * (LOW, MODERATE, HIGH, ERROR) - report: The report file.
//			 */
//			MultipartEntity entity = new MultipartEntity();
//			entity.addPart("command",
//					new StringBody("SUBMIT_REPORT", Charset.forName("UTF-8")));
//			entity.addPart("username", new StringBody(
//					Properties.appvetUsername, Charset.forName("UTF-8")));
//			entity.addPart("password", new StringBody(
//					Properties.appvetPassword, Charset.forName("UTF-8")));
//			entity.addPart("appid",
//					new StringBody(appId, Charset.forName("UTF-8")));
//			entity.addPart("toolid",
//					new StringBody(Properties.toolId, Charset.forName("UTF-8")));
//			entity.addPart("toolrisk", new StringBody(reportStatus.name(),
//					Charset.forName("UTF-8")));
//			File report = new File(reportFilePath);
//			FileBody fileBody = new FileBody(report);
//			entity.addPart("file", fileBody);
//			HttpPost httpPost = new HttpPost(Properties.appvetUrl);
//			httpPost.setEntity(entity);
//			// Send the report to AppVet
//			log.debug("Sending report file to AppVet");
//			final HttpResponse response = httpClient.execute(httpPost);
//			log.debug("Received from AppVet: " + response.getStatusLine());
//			// Clean up
//			httpPost = null;
//			return true;
//		} catch (Exception e) {
//			log.error(e.toString());
//			return false;
//		}
//	}
}
