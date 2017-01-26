/**********************************************************************************
 *
 * Copyright (c) 2017 Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/
package org.sakaiproject.contentreview.impl.urkund;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.validator.routines.EmailValidator;
import org.sakaiproject.api.common.edu.person.SakaiPerson;
import org.sakaiproject.api.common.edu.person.SakaiPersonManager;
import org.sakaiproject.assignment.api.AssignmentSubmission;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.contentreview.impl.hbm.BaseReviewServiceImpl;
import org.sakaiproject.contentreview.model.ContentReviewItem;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entitybroker.EntityReference;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.ServerOverloadException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.genericdao.api.search.Restriction;
import org.sakaiproject.genericdao.api.search.Search;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.util.ResourceLoader;

public class UrkundReviewServiceImpl extends BaseReviewServiceImpl {
	
	private static final Log log = LogFactory.getLog(UrkundReviewServiceImpl.class);
	
	private static final String STATE_SUBMITTED = "Submitted";
	private static final String STATE_ACCEPTED = "Accepted";
	private static final String STATE_REJECTED = "Rejected";
	private static final String STATE_ANALYZED = "Analyzed";
	private static final String STATE_ERROR = "Error";
	
	private static final String SERVICE_NAME = "Urkund";
	
	// Site property to enable or disable use of Urkund for the site
	private static final String URKUND_SITE_PROPERTY = "urkund";
	
	// 0 is unique user ID (must include friendly email address characters only)
    // 1 is unique site ID (must include friendly email address characters only)
    // 2 is integration context string (must be 2 to 10 characters)
    private static final String URKUND_SPOOFED_EMAIL_TEMPLATE = "%s_%s.%s@submitters.urkund.com";
    private String spoofEmailContext;
	
	// Define Urkund's acceptable file extensions and MIME types, order of these arrays DOES matter
	private final String[] DEFAULT_ACCEPTABLE_FILE_EXTENSIONS = new String[] {
			".doc", 
			".docx",
			".sxw",
			".ppt", 
			".pptx", 
			".pdf", 
			".txt", 
			".rtf", 
			".html", 
			".htm", 
			".wps",
			".odt"
	};
	private final String[] DEFAULT_ACCEPTABLE_MIME_TYPES = new String[] {
			"application/msword", 
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document", 
			"application/vnd.sun.xml.writer",  
			"application/vnd.ms-powerpoint", 
			"application/vnd.openxmlformats-officedocument.presentationml.presentation",
			"application/pdf",
			"text/plain", 
			"application/rtf", 
			"text/html", 
			"text/html", 
			"application/vnd.ms-works", 
			"application/vnd.oasis.opendocument.text"
	};
	
	// Sakai.properties overriding the arrays above
	private final String PROP_ACCEPT_ALL_FILES = "urkund.accept.all.files";

	private final String PROP_ACCEPTABLE_FILE_EXTENSIONS = "urkund.acceptable.file.extensions";
	private final String PROP_ACCEPTABLE_MIME_TYPES = "urkund.acceptable.mime.types";

	// A list of the displayable file types (ie. "Microsoft Word", "WordPerfect document", "Postscript", etc.)
	private final String PROP_ACCEPTABLE_FILE_TYPES = "urkund.acceptable.file.types";

	private final String KEY_FILE_TYPE_PREFIX = "file.type";
	
	final static long LOCK_PERIOD = 12000000;
	private Long maxRetry = 20L;
	
	protected ServerConfigurationService serverConfigurationService;
	protected ContentHostingService contentHostingService;
	protected SakaiPersonManager sakaiPersonManager;
	protected UrkundAccountConnection urkundConn;
	protected UrkundContentValidator urkundContentValidator;
	
	public void setUrkundConn(UrkundAccountConnection urkundConn) {
		this.urkundConn = urkundConn;
	}
	public void setContentHostingService(ContentHostingService contentHostingService) {
		this.contentHostingService = contentHostingService;
	}
	public void setServerConfigurationService(ServerConfigurationService serverConfigurationService) {
		this.serverConfigurationService = serverConfigurationService;
	}
	public void setUrkundContentValidator(UrkundContentValidator urkundContentValidator) {
		this.urkundContentValidator = urkundContentValidator;
	}
    public void setSakaiPersonManager(SakaiPersonManager sakaiPersonManager) {
        this.sakaiPersonManager = sakaiPersonManager;
    }
	
	public void init() {
		maxRetry = Long.valueOf(serverConfigurationService.getInt("urkund.maxRetry", 20));
		spoofEmailContext = serverConfigurationService.getString("urkund.spoofemailcontext", null);
	}

	/* --------------------------------------------------------------------
	 * Implementing ContentReviewService methods
	 * --------------------------------------------------------------------
	 */
	@Override
	public String getServiceName() {
		return SERVICE_NAME;
	}

	@Override
	public int getReviewScore(String contentId, String assignmentRef, String userId) throws QueueException, ReportException, Exception {
		ContentReviewItem item = null;
		try {
			List<ContentReviewItem> matchingItems = getItemsByContentId(contentId);
			if (matchingItems.size() == 0) {
				log.debug("Content " + contentId + " has not been queued previously");
			}
			if (matchingItems.size() > 1)
				log.debug("More than one matching item - using first item found");

			item = (ContentReviewItem) matchingItems.iterator().next();
			if (item.getStatus().compareTo(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE) != 0) {
				log.debug("Report not available: " + item.getStatus());
			}
		} catch (Exception e) {
			log.error("(getReviewScore)" + e);
		}

		return item.getReviewScore().intValue();
	}

	@Override
	public String getReviewReport(String contentId, String assignmentRef, String userId) throws QueueException, ReportException {

		Search search = new Search();
		search.addRestriction(new Restriction("contentId", contentId));
		List<ContentReviewItem> matchingItems = dao.findBySearch(ContentReviewItem.class, search);
		if (matchingItems.size() == 0) {
			log.debug("Content " + contentId + " has not been queued previously");
			throw new QueueException("Content " + contentId + " has not been queued previously");
		}

		if (matchingItems.size() > 1)
			log.debug("More than one matching item found - using first item found");

		// check that the report is available
		// TODO if the database record does not show report available check with
		// urkund (maybe)

		ContentReviewItem item = (ContentReviewItem) matchingItems.iterator().next();
		if (item.getStatus().compareTo(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE) != 0) {
			log.debug("Report not available: " + item.getStatus());
			throw new ReportException("Report not available: " + item.getStatus());
		}

		List<UrkundSubmissionData> submissionDataList = urkundConn.getReports(item.getExternalId());
		
		String reportURL = null;
		for(UrkundSubmissionData submissionData : submissionDataList) {
			if (submissionData != null) {
				if(submissionData.getExternalId() != null && submissionData.getExternalId().equals(item.getExternalId())){
					if(STATE_ANALYZED.equals(submissionData.getStatus().get("State"))) {
						reportURL = (String)submissionData.getReport().get("ReportUrl");
					}
				}
			} else {
				log.error("Error retrieving Urkund report URL");
			}
		}
		
		return reportURL;
	}

	@Override
	public String getReviewReportInstructor(String contentId, String assignmentRef, String userId) throws QueueException, ReportException {
		return getReviewReport(contentId, assignmentRef, userId);
	}
	
	@Override	
	public String getReviewReportStudent(String contentId, String assignmentRef, String userId) throws QueueException, ReportException {
		return getReviewReport(contentId, assignmentRef, userId);
	}

	@Override
	public void processQueue() {

		log.info("Processing submission queue");
		int errors = 0;
		int success = 0;

		//first get not uploaded items
		for (ContentReviewItem currentItem = getNextItemWithoutExternalId(); currentItem != null; currentItem = getNextItemWithoutExternalId()) {
			log.debug("Attempting to upload content (status:"+currentItem.getStatus()+"): " + currentItem.getContentId() + " for user: "
					+ currentItem.getUserId() + " and site: " + currentItem.getSiteId());						

			if(!processItem(currentItem)){
				errors++;
				continue;
			}
			
			//if document has no external id, we need to add it to urkund
			if(StringUtils.isBlank(currentItem.getExternalId())) {
				//check if we have added it correctly
				if(addDocumentToUrkund(currentItem) == false){
					errors++;
				}
			}
			
			// release the lock so the reports job can handle it
			releaseLock(currentItem);
		}
		
		//get documents to analyze
		for (ContentReviewItem currentItem = getNextItemInSubmissionQueue(); currentItem != null; currentItem = getNextItemInSubmissionQueue()) {

			log.debug("Attempting to submit content (status:"+currentItem.getStatus()+"): " + currentItem.getContentId() + " for user: "
					+ currentItem.getUserId() + " and site: " + currentItem.getSiteId());						

			if(!processItem(currentItem)){
				errors++;
				continue;
			}
			

			List<UrkundSubmissionData> submissionDataList = urkundConn.getReports(currentItem.getExternalId());
			
			//TODO : CHECK THIS : get the first matching report
			UrkundSubmissionData submissionData = null;
			for(UrkundSubmissionData sd : submissionDataList) {
				if (sd != null) {
					if(sd.getExternalId() != null && sd.getExternalId().equals(currentItem.getExternalId())){
						submissionData = sd;
						break;
					}
				}
			}

			if (submissionData != null) {
				try {
					if(STATE_ACCEPTED.equals(submissionData.getStatus().get("State"))) {
						log.debug("Submission successful");
						currentItem.setStatus(ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE);
						currentItem.setRetryCount(Long.valueOf(0));
						currentItem.setNextRetryTime(new Date());
						currentItem.setLastError(null);
						currentItem.setErrorCode(null);
						success++;
						dao.update(currentItem);
					} else if(STATE_ANALYZED.equals(submissionData.getStatus().get("State"))) {
						currentItem.setReviewScore((int) Math.round(submissionData.getSignificance()));
						currentItem.setStatus(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE);
						success++;
						dao.update(currentItem);
					} else if(STATE_REJECTED.equals(submissionData.getStatus().get("State"))) {
						processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE, "Submission Rejected : "+submissionData.getStatus().get("Message"), null);
						errors++;
					} else if(STATE_ERROR.equals(submissionData.getStatus().get("State"))) {
						processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE, "Submission Error : "+submissionData.getStatus().get("Message"), null);
						errors++;
					} else {
						processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Submission Unknown State ("+submissionData.getStatus().get("State")+") : "+submissionData.getStatus().get("Message"), null);
						errors++;
					}
				
				} catch(Exception e){
					processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Exception processing submission data : "+e.getMessage(), null);
					errors++;
				}
			} else {
				processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Submission Error (Submission Data is null)", null);
				errors++;
			}
			// release the lock so the reports job can handle it
			releaseLock(currentItem);
		}

		log.info("Submission queue run completed: " + success + " items submitted, " + errors + " errors.");
	}

	@SuppressWarnings({ "deprecation" })
	@Override
	public void checkForReports() {

		log.info("Fetching reports from Urkund");

		// get the list of all items that are waiting for reports
		Search search = new Search();
		search.setConjunction(false); //OR clauses
		search.addRestriction(new Restriction("status", ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE));
		search.addRestriction(new Restriction("status", ContentReviewItem.REPORT_ERROR_RETRY_CODE));
		List<ContentReviewItem> awaitingReport = dao.findBySearch(ContentReviewItem.class, search);

		Iterator<ContentReviewItem> listIterator = awaitingReport.iterator();

		log.debug("There are " + awaitingReport.size() + " submissions awaiting reports");

		int errors = 0;
		int success = 0;
		int inprogress = 0;
		ContentReviewItem currentItem;
		while (listIterator.hasNext()) {
			currentItem = (ContentReviewItem) listIterator.next();

			// has the item reached its next retry time?
			if (currentItem.getNextRetryTime() == null) {
				currentItem.setNextRetryTime(new Date());
			}

			if (currentItem.getNextRetryTime().after(new Date())) {
				// we haven't reached the next retry time
				log.info("checkForReports :: next retry time not yet reached for item: " + currentItem.getId());
				dao.update(currentItem);
				continue;
			}

			if(!processItem(currentItem)){
				errors++;
				continue;
			}

			//back to analysis (this should not happen)
			if (StringUtils.isBlank(currentItem.getExternalId())) {
				currentItem.setStatus(Long.valueOf(ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE));
				dao.update(currentItem);
				errors++;
				continue;
			}

			List<UrkundSubmissionData> submissionDataList = urkundConn.getReports(currentItem.getExternalId());
			
			//TODO : CHECK THIS : get the first matching report
			UrkundSubmissionData submissionData = null;
			for(UrkundSubmissionData sd : submissionDataList) {
				if (sd != null) {
					if(sd.getExternalId() != null && sd.getExternalId().equals(currentItem.getExternalId())){
						submissionData = sd;
						break;
					}
				}
			}
			
			if (submissionData != null) {
				if(STATE_ANALYZED.equals(submissionData.getStatus().get("State"))) {
					currentItem.setReviewScore((int) Math.round(submissionData.getSignificance()));
					currentItem.setStatus(ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE);
					dao.update(currentItem);
					success++;
				} else if(STATE_ACCEPTED.equals(submissionData.getStatus().get("State"))) {
					inprogress++;
				}
			} else {
				processError(currentItem, ContentReviewItem.REPORT_ERROR_RETRY_CODE, null, null);
				errors++;
				continue;
			}
		}

		log.info("Finished fetching reports from Urkund : "+success+" success items, "+inprogress+" in progress, "+errors+" errors");
	}
	
	

	@Override
	public boolean allowAllContent() {
		return serverConfigurationService.getBoolean(PROP_ACCEPT_ALL_FILES, false);
	}

	@Override
	public boolean isAcceptableContent(ContentResource resource) {
		return urkundContentValidator.isAcceptableContent(resource);
	}

	@Override
	public Map<String, SortedSet<String>> getAcceptableExtensionsToMimeTypes()
	{
		Map<String, SortedSet<String>> acceptableExtensionsToMimeTypes = new HashMap<>();
		String[] acceptableFileExtensions = getAcceptableFileExtensions();
		String[] acceptableMimeTypes = getAcceptableMimeTypes();
		int min = Math.min(acceptableFileExtensions.length, acceptableMimeTypes.length);
		for (int i = 0; i < min; i++)
		{
			appendToMap(acceptableExtensionsToMimeTypes, acceptableFileExtensions[i], acceptableMimeTypes[i]);
		}

		return acceptableExtensionsToMimeTypes;
	}

	@Override
	public Map<String, SortedSet<String>> getAcceptableFileTypesToExtensions()
	{
		Map<String, SortedSet<String>> acceptableFileTypesToExtensions = new LinkedHashMap<>();
		String[] acceptableFileTypes = getAcceptableFileTypes();
		String[] acceptableFileExtensions = getAcceptableFileExtensions();
		if (acceptableFileTypes != null && acceptableFileTypes.length > 0)
		{
			// The acceptable file types are listed in sakai.properties. Sakai.properties takes precedence.
			int min = Math.min(acceptableFileTypes.length, acceptableFileExtensions.length);
			for (int i = 0; i < min; i++)
			{
				appendToMap(acceptableFileTypesToExtensions, acceptableFileTypes[i], acceptableFileExtensions[i]);
			}
		}
		else
		{
			/*
			 * acceptableFileTypes not specified in sakai.properties (this is normal).
			 * Use ResourceLoader to resolve the file types.
			 * If the resource loader doesn't find the file extenions, log a warning and return the [missing key...] messages
			 */
			ResourceLoader resourceLoader = new ResourceLoader("urkund");
			for( String fileExtension : acceptableFileExtensions )
			{
				String key = KEY_FILE_TYPE_PREFIX + fileExtension;
				if (!resourceLoader.getIsValid(key))
				{
					log.warn("While resolving acceptable file types for Urkund, the sakai.property " + PROP_ACCEPTABLE_FILE_TYPES + " is not set, and the message bundle " + key + " could not be resolved. Displaying [missing key ...] to the user");
				}
				String fileType = resourceLoader.getString(key);
				appendToMap( acceptableFileTypesToExtensions, fileType, fileExtension );
			}
		}

		return acceptableFileTypesToExtensions;
	}

	@Override
	public boolean isSiteAcceptable(Site site) {
		if (site == null) {
			return false;
		}

		log.debug("isSiteAcceptable: " + site.getId() + " / " + site.getTitle());

		// Delegated to another bean
		if (siteAdvisor != null) {
			return siteAdvisor.siteCanUseReviewService(site);
		}

		// Check site property
		ResourceProperties properties = site.getProperties();

		String prop = (String) properties.get(URKUND_SITE_PROPERTY);
		if (StringUtils.isNotBlank(prop)) {
			log.debug("Using site property: " + prop);
			return Boolean.parseBoolean(prop);
		}

		// No property set, no restriction on site types, so allow
		return true;
	}

	@Override
	public String getIconUrlforScore(Long score) {
		String urlBase = "/sakai-contentreview-tool-federated/images/score_";
		String suffix = ".gif";

		if (score.equals(Long.valueOf(0))) {
			return urlBase + "green" + suffix;
		} else if (score.compareTo(Long.valueOf(39)) <= 0) {
			return urlBase + "yellow" + suffix;
		} else if (score.compareTo(Long.valueOf(54)) <= 0) {
			return urlBase + "orange" + suffix;
		} else {
			return urlBase + "red" + suffix;
		}
	}

	@Override
	public void removeFromQueue(String ContentId) {
		List<ContentReviewItem> object = getItemsByContentId(ContentId);
		dao.delete(object);
	}

	@Override
	public String getLocalizedStatusMessage(String messageCode, String userRef) {
		String userId = EntityReference.getIdFromRef(userRef);
		ResourceLoader resourceLoader = new ResourceLoader(userId, "urkund");
		return resourceLoader.getString(messageCode);
	}

	@Override
	public String getLocalizedStatusMessage(String messageCode) {
		return getLocalizedStatusMessage(messageCode, userDirectoryService.getCurrentUser().getReference());
	}
	
	@Override
	public String getLocalizedStatusMessage(String messageCode, Locale locale) {
		// TODO not sure how to do this with the sakai resource loader
		return null;
	}

	@Override
	public String getReviewError(String contentId) {
		return getLocalizedReviewErrorMessage(contentId);
	}

	@Override
	public Map getAssignment(String siteId, String taskId) throws SubmissionException, TransientSubmissionException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void createAssignment(String siteId, String taskId, Map extraAsnnOpts) throws SubmissionException, TransientSubmissionException {
		// TODO Auto-generated method stub
		
	}

	
	//-----------------------------------------------------------------------------
	// Extra methods
	//-----------------------------------------------------------------------------
	//TODO : add error codes every time 'processError' is called, so we can set i18 messages
	private String getLocalizedReviewErrorMessage(String contentId) {
		log.debug("Returning review error for content: " + contentId);

		List<ContentReviewItem> matchingItems = dao.findByExample(new ContentReviewItem(contentId));

		if (matchingItems.size() == 0) {
			log.debug("Content " + contentId + " has not been queued previously");
			return null;
		}

		if (matchingItems.size() > 1) {
			log.debug("more than one matching item found - using first item found");
		}

		// its possible the error code column is not populated
		Integer errorCode = ((ContentReviewItem) matchingItems.iterator().next()).getErrorCode();
		if (errorCode == null) {
			return ((ContentReviewItem) matchingItems.iterator().next()).getLastError();
		}
		return getLocalizedStatusMessage(errorCode.toString());
	}
	
	/**
	 * find the next time this item should be tried
	 * 
	 * @param retryCount
	 * @return
	 */
	private Date getNextRetryTime(long retryCount) {
		Double offset = 5.;

		if(retryCount > 0) {
			offset = 15 * Math.pow(2, retryCount-1);
		}
		if(offset > 480) {
			offset = 480.;
		}

		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.MINUTE, offset.intValue());
		return cal.getTime();
	}
	
	private ContentReviewItem getNextItemInSubmissionQueue() {

		// Submit items that haven't yet been submitted
		Search search = new Search();
		search.addRestriction(new Restriction("status", ContentReviewItem.NOT_SUBMITTED_CODE));
		search.addRestriction(new Restriction("externalId", "", Restriction.NOT_NULL));
		List<ContentReviewItem> notSubmittedItems = dao.findBySearch(ContentReviewItem.class, search);
		ContentReviewItem nextItem = getItemPastRetryTime( notSubmittedItems );
		if( nextItem != null )
		{
			return nextItem;
		}
		
		// Submit items that should be retried
		search = new Search();
		search.addRestriction(new Restriction("status", ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE));
		search.addRestriction(new Restriction("externalId", "", Restriction.NOT_NULL));
		notSubmittedItems = dao.findBySearch(ContentReviewItem.class, search);
		
		nextItem = getItemPastRetryTime( notSubmittedItems );
		if( nextItem != null )
		{
			return nextItem;
		}

		return null;
	}
	
	private ContentReviewItem getNextItemWithoutExternalId() {


		// Submit items that haven't yet been submitted
		Search search = new Search();
		search.addRestriction(new Restriction("status", ContentReviewItem.NOT_SUBMITTED_CODE));
		search.addRestriction(new Restriction("externalId", "", Restriction.NULL));
		List<ContentReviewItem> notSubmittedItems = dao.findBySearch(ContentReviewItem.class, search);
		ContentReviewItem nextItem = getItemPastRetryTime( notSubmittedItems );
		if( nextItem != null )
		{
			return nextItem;
		}
		
		// Submit items that should be retried
		search = new Search();
		search.addRestriction(new Restriction("status", ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE));
		search.addRestriction(new Restriction("externalId", "", Restriction.NULL));
		notSubmittedItems = dao.findBySearch(ContentReviewItem.class, search);
		nextItem = getItemPastRetryTime( notSubmittedItems );
		if( nextItem != null )
		{
			return nextItem;
		}
		
		// Submit items that should be retried (invalid user details)
		search = new Search();
		search.addRestriction(new Restriction("status", ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE));
		search.addRestriction(new Restriction("externalId", "", Restriction.NULL));
		notSubmittedItems = dao.findBySearch(ContentReviewItem.class, search);
		nextItem = getItemPastRetryTime( notSubmittedItems );
		if( nextItem != null )
		{
			return nextItem;
		}

		return null;
	}
	
	/**
	 * Returns the first item in the list which has surpassed it's next retry time, and we can get a lock on the object.
	 * Otherwise returns null.
	 * 
	 * @param Items the list of ContentReviewItems to iterate over.
	 * @return the first item in the list that meets the requirements, or null.
	 */

	private ContentReviewItem getItemPastRetryTime( List<ContentReviewItem> items)
	{
		for( ContentReviewItem item : items )
		{
			if( hasReachedRetryTime( item ) && obtainLock( "item." + item.getId().toString() ) )
			{
				return item;
			}
		}

		return null;
	}
	
	private boolean hasReachedRetryTime(ContentReviewItem item) {
		// has the item reached its next retry time?
		if (item.getNextRetryTime() == null)
		{
			item.setNextRetryTime(new Date());
		}

		if (item.getNextRetryTime().after(new Date())) {
			//we haven't reached the next retry time
			log.debug("next retry time not yet reached for item: " + item.getId());
			dao.update(item);
			return false;
		}

		return true;
	}
	
	private boolean addDocumentToUrkund(ContentReviewItem currentItem) {
		// to get the name of the initial submited file we need the title
		ContentResource resource = null;
		String fileName = null;
		try {
			try {
				resource = contentHostingService.getResource(currentItem.getContentId());
				
				//this never should happen, user can not add to queue invalid files
				if(!urkundContentValidator.isAcceptableContent(resource)){
					log.error("Not valid extension: resource with id " + currentItem.getContentId());
					processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE, "Not valid extension: resource with id " + currentItem.getContentId(), null);
					return false;
				}

			} catch (TypeException e4) {

				log.warn("TypeException: resource with id " + currentItem.getContentId());
				processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE, "TypeException: resource with id " + currentItem.getContentId(), null);
				return false;
			} catch (IdUnusedException e) {
				log.warn("IdUnusedException: no resource with id " + currentItem.getContentId());
				processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_NO_RETRY_CODE, "IdUnusedException: no resource with id " + currentItem.getContentId(), null);
				return false;
			}
			ResourceProperties resourceProperties = resource.getProperties();
			fileName = resourceProperties.getProperty(resourceProperties.getNamePropDisplayName());
			fileName = escapeFileName(fileName, resource.getId());
			if("true".equals(resourceProperties.getProperty(AssignmentSubmission.PROP_INLINE_SUBMISSION))) {
				fileName += ".html";
			}
		} catch (PermissionException e2) {
			log.error("Submission failed due to permission error.", e2);
			processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Permission exception: " + e2.getMessage(), null);
			return false;
		}
		
		User user;
        try {
            user = userDirectoryService.getUser(currentItem.getUserId());
        } catch (UserNotDefinedException e1) {
            log.error("Submission attempt unsuccessful - User not found.", e1);
            processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "User not found : Contact Service desk for help", null);
            return false;
        }

        String submitterEmail = getEmail(user, currentItem.getSiteId());
        log.debug("Using email = " + submitterEmail + ", for user eid = " + user.getEid() + ", id = " + user.getId() + ", site id = "+currentItem.getSiteId());
        
        if (submitterEmail == null) {
            log.error("User: " + user.getEid() + " has no valid email");
            processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_USER_DETAILS_CODE, "Invalid user email : Contact Service desk for help", null);
            return false;
        }
		
		String externalId = contentHostingService.getUuid(resource.getId())+"-"+(new Date()).getTime();
		UrkundSubmissionData submissionData = null;
		try {
			submissionData = urkundConn.uploadFile(submitterEmail, externalId, fileName, resource.getContent(), resource.getContentType());
		} catch (ServerOverloadException e) {
			log.error("Submission failed.", e);
			processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Upload exception: " + e.getMessage(), null);
			return false;
		}
		
		if(submissionData != null){
			if(STATE_SUBMITTED.equals(submissionData.getStatus().get("State"))) {
				log.debug("Submission successful");
				currentItem.setExternalId(externalId);
				currentItem.setStatus(ContentReviewItem.NOT_SUBMITTED_CODE);
				currentItem.setRetryCount(Long.valueOf(0));
				currentItem.setNextRetryTime(new Date());
				currentItem.setLastError(null);
				currentItem.setErrorCode(null);
				currentItem.setDateSubmitted(new Date());
				dao.update(currentItem);
				return true;
			}
		}
		processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE, "Add Document To Urkund Error", null);
		return false;
	}
	
	public String escapeFileName(String fileName, String contentId) {
		log.debug("original filename is: " + fileName);
		if (fileName == null) {
			// use the id
			fileName = contentId;
		}
		log.debug("fileName is :" + fileName);
		try {
			fileName = URLDecoder.decode(fileName, "UTF-8");
			// in rare cases it seems filenames can be double encoded
			while (fileName.indexOf("%20") > 0 || fileName.contains("%2520")) {
				fileName = URLDecoder.decode(fileName, "UTF-8");
			}
		} catch (IllegalArgumentException | UnsupportedEncodingException eae) {
			log.warn("Unable to decode fileName: " + fileName, eae);
			return contentId;
		}

		fileName = fileName.replace(' ', '_');
		// its possible we have double _ as a result of this lets do some
		// cleanup
		fileName = StringUtils.replace(fileName, "__", "_");

		log.debug("fileName is :" + fileName);
		return fileName;
	}

	private boolean obtainLock(String itemId) {
		Boolean lock = dao.obtainLock(itemId, serverConfigurationService.getServerId(), LOCK_PERIOD);
		return (lock != null) ? lock : false;
	}
	
	private void releaseLock(ContentReviewItem currentItem) {
		dao.releaseLock("item." + currentItem.getId().toString(), serverConfigurationService.getServerId());
	}
	
	private String[] getAcceptableMimeTypes()
	{
		String[] mimeTypes = serverConfigurationService.getStrings(PROP_ACCEPTABLE_MIME_TYPES);
		if (mimeTypes != null && mimeTypes.length > 0)
		{
			return mimeTypes;
		}
		return DEFAULT_ACCEPTABLE_MIME_TYPES;
	}
	
	private String[] getAcceptableFileExtensions()
	{
		String[] extensions = serverConfigurationService.getStrings(PROP_ACCEPTABLE_FILE_EXTENSIONS);
		if (extensions != null && extensions.length > 0)
		{
			return extensions;
		}
		return DEFAULT_ACCEPTABLE_FILE_EXTENSIONS;
	}
	
	private String [] getAcceptableFileTypes()
	{
		return serverConfigurationService.getStrings(PROP_ACCEPTABLE_FILE_TYPES);
	}
	
	/**
	 * Inserts (key, value) into a Map<String, Set<String>> such that value is inserted into the value Set associated with key.
	 * The value set is implemented as a TreeSet, so the Strings will be in alphabetical order
	 * Eg. if we insert (a, b) and (a, c) into map, then map.get(a) will return {b, c}
	 */
	private void appendToMap(Map<String, SortedSet<String>> map, String key, String value)
	{
		SortedSet<String> valueList = map.get(key);
		if (valueList == null)
		{
			valueList = new TreeSet<>();
			map.put(key, valueList);
		}
		valueList.add(value);
	}
	
	private void processError( ContentReviewItem item, Long status, String error, Integer errorCode )
	{
		try
		{
			if( status == null )
			{
				IllegalArgumentException ex = new IllegalArgumentException( "Status is null; you must supply a valid status to update when calling processError()" );
				throw ex;
			}
			else
			{
				item.setStatus( status );
			}
			if( error != null )
			{
				item.setLastError(error);
			}
			if( errorCode != null )
			{
				item.setErrorCode( errorCode );
			}

			dao.update( item );
		}
		finally
		{
			releaseLock( item );
		}
	}
	
	private boolean processItem(ContentReviewItem currentItem){
		if (currentItem.getRetryCount() == null) {
			currentItem.setRetryCount(Long.valueOf(0));
			currentItem.setNextRetryTime(this.getNextRetryTime(0));
		} else if (currentItem.getRetryCount().intValue() > maxRetry) {
			processError(currentItem, ContentReviewItem.SUBMISSION_ERROR_RETRY_EXCEEDED, null, null);
			return false;
		} else {
			long l = currentItem.getRetryCount().longValue();
			l++;
			currentItem.setRetryCount(Long.valueOf(l));
			currentItem.setNextRetryTime(this.getNextRetryTime(Long.valueOf(l)));
		}
		dao.update(currentItem);
		
		return true;
	}
	
	// returns null if no valid email exists
    private String getEmail(User user, String siteId) {
    	
    	if (spoofEmailContext != null && spoofEmailContext.length() >= 2 && spoofEmailContext.length() <= 10) {
    		return String.format(URKUND_SPOOFED_EMAIL_TEMPLATE, user.getId(), siteId, spoofEmailContext);
    	}

    	String ret = null;

    	// Check account email address
        if (isValidEmail(user.getEmail())) {
            ret = user.getEmail().trim();
        }

        // Lookup system profile email address if necessary
        if (ret == null) {
            SakaiPerson sp = sakaiPersonManager.getSakaiPerson(user.getId(), sakaiPersonManager.getSystemMutableType());
            if (sp != null && isValidEmail(sp.getMail())) {
                ret = sp.getMail().trim();
            }
        }

        return ret;
    }

    /**
     * Is this a valid email the service will recognize
     *
     * @param email
     * @return
     */
    private boolean isValidEmail(String email) {

        if (email == null || email.equals("")) {
            return false;
        }

        email = email.trim();
        //must contain @
        if (!email.contains("@")) {
            return false;
        }

        //an email can't contain spaces
        if (email.indexOf(" ") > 0) {
            return false;
        }

        //use commons-validator
        EmailValidator validator = EmailValidator.getInstance();
        return validator.isValid(email);
    }
}
