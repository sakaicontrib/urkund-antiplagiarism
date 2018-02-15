# Urkund : Integration with Sakai 11.x

This project integrates Urkund plagiarism system with Sakai LMS.

Our first rule is not to modify the ContentReview API (item models or service interfaces), so the integration should be much more standard.

This project contains the Urkund implementation for ContentReview


## Instructions:

- Clone this project : https://github.com/sakaicontrib/urkund-antiplagiarism
- Compile our contentreview-impl
- If your Sakai version is prior to 11.3, patch your Sakai instance with this changes https://github.com/sakaiproject/sakai/pull/3830
- If your Sakai version is prior to 11.5, patch your Sakai instance with SAK-33617.patch or use this changes https://github.com/sakaiproject/sakai/pull/5019
- Enable the urkund integration in the content-review module and compile it (Sakai source).
  - Open this file content-review/contentreview-federated/pack/src/webapp/WEB-INF/components.xml
  - Uncomment the line bean="org.sakaiproject.contentreview.service.ContentReviewServiceUrkund" to enable the Urkund content review implementation
- Set up the correct sakai properties
- Remembers to set up Quartz jobs : You will have to run jobs manually unless they're set up to auto-run.

## Sakai properties

- assignment.useContentReview=true
- urkund.address=URKUND_RECEIVER_ID (EMAIL)
- urkund.username=URKUND_ACCESS_USERNAME
- urkund.password=URKUND_ACCESS_PASSWORD

#### Optional  (or with default value)
  - urkund.apiURL=https://secure.urkund.com/api/
  - urkund.maxFileSize=20971520
  - urkund.maxRetry=20
  - urkund.spoofemailcontext=CONTEXT (string from 2 to 10 characters)
  - urkund.accept.all.files=false
  - urkund.acceptable.mime.types=application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.sun.xml.writer,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation,application/pdf,text/plain,application/rtf,text/html,text/html,application/vnd.ms-works,application/vnd.oasis.opendocument.text
  - urkund.acceptable.file.extensions=.doc,.docx,.sxw,.ppt,.pptx,.pdf,.txt,.rtf,.html,.htm,.wps,.odt
  - urkund.acceptable.file.types=ACCEPTABLE_FILE_TYPES
  - urkund.networkTimeout=180000

## IMPORTANT : DB Migration
If you have items created before this commit bc8e982 (15/12/2017), you need to run this script in your DB :
```sql
INSERT INTO contentreview_item_urkund (id) (SELECT id FROM contentreview_item ci WHERE NOT EXISTS (SELECT * FROM contentreview_item_urkund ciu WHERE ci.id = ciu.id));
```

## Quartz Jobs

- Process Content Review Queue : Process the content-review queue, uploads documents to Urkund and analyze them.
- Process Content Review Reports : Get reports for analyzed documents.

Create a new trigger for this jobs. We suggest you set the job to run at five minute intervals which can be done with the following definition:
```
0 0/5 * * * ?
```

## Assignments set up

Options in the assignment creation screen : 
- Use Urkund : Basic check to activate the plagiarism system
- Allow students to view report : Let the students get a link to the Urkund results report.
- Generate originality reports : 
  * "Immediately" : Documents will be uploaded and analyzed in the moment the student uploads them (the next time the job runs after the students upload them). CAUTION : this may cause an incorrect analysis report due to the impossibility to compare with all documents.
  * "On Due Date" : Document will be uploaded and analyzed after the assignment reaches its due date.

## Components.xml set up
* Basic set up
```xml
<bean id="org.sakaiproject.contentreview.service.ContentReviewServiceUrkund"
  class="org.sakaiproject.contentreview.impl.urkund.UrkundReviewServiceImpl"
  init-method="init">
  <property name="dao" ref="org.sakaiproject.contentreview.dao.ContentReviewDao" />
  
  <property name="toolManager" ref="org.sakaiproject.tool.api.ToolManager" />
  <property name="userDirectoryService" ref="org.sakaiproject.user.api.UserDirectoryService" />
  <property name="serverConfigurationService" ref="org.sakaiproject.component.api.ServerConfigurationService" />
  <property name="contentHostingService" ref="org.sakaiproject.content.api.ContentHostingService" />
  <property name="assignmentService" ref="org.sakaiproject.assignment.api.AssignmentService" />
  <property name="entityManager" ref="org.sakaiproject.entity.api.EntityManager" />
  <property name="sakaiPersonManager" ref="org.sakaiproject.api.common.edu.person.SakaiPersonManager" />
  
  <property name="urkundConn" ref="org.sakaiproject.contentreview.impl.urkund.UrkundAccountConnection" />
  <property name="urkundContentValidator" ref="org.sakaiproject.contentreview.impl.urkund.UrkundContentValidator" />
    
  <!-- Uncomment this to delegate into advisors -->
  <!-- <property name="siteAdvisor" ref="org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor" /> -->
</bean>
```

* Advisors (disabled by default)
```xml
<!-- Uncomment this to allow all sites to use Urkund regardless of site, type, or property -->
<!-- <bean id="org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor" 
	class="org.sakaiproject.contentreview.impl.adivisors.DefaultSiteAdvisor"> 
</bean> -->
	
<!-- Uncomment this to use a site property to define which sites use c-r -->
<!-- <bean id="org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor" 
	class="org.sakaiproject.contentreview.impl.adivisors.SitePropertyAdvisor"> 
	<property name="siteProperty"><value>useContentReviewService</value></property> 
</bean> -->
	
<!-- uncomment this bean to make c-r available to only sites of the type course -->
<!-- <bean id="org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor" 
	class="org.sakaiproject.contentreview.impl.adivisors.SiteCourseTypeAdvisor"> 
</bean> -->
	
<!--  Uncomment this to use a global property to define if every site uses c-r -->
<!--<bean id="org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor" 
	class="org.sakaiproject.contentreview.impl.advisors.GlobalPropertyAdvisor">
	<property name="sakaiProperty"><value>assignment.useContentReview</value></property>
	<property name="serverConfigurationService"
		ref="org.sakaiproject.component.api.ServerConfigurationService" />
</bean>-->

<!-- uncomment this bean to make c-r available using chained advisors -->
<!--
<bean id="org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor"
	  class="org.sakaiproject.contentreview.impl.advisors.ChainedPropertyAdvisor">
	<property name="advisors">
		<list>
			<bean class="org.sakaiproject.contentreview.impl.advisors.SitePropertyAdvisor">
				<property name="siteProperty">
					<value>useContentReviewService</value>
				</property>
			</bean>
			<bean class="org.sakaiproject.contentreview.impl.advisors.GlobalPropertyAdvisor">
				<property name="sakaiProperty">
					<value>assignment.useContentReview</value>
				</property>
				<property name="serverConfigurationService"
						  ref="org.sakaiproject.component.api.ServerConfigurationService"/>
			</bean>
		</list>
	</property>
</bean> -->
```
## Content Review Service : basic configuration

The original content review service provided by Sakai is prepared to add multiple implementations. Remember to activate the Urkund one (.../content-review/contentreview-federated/pack/src/webapp/WEB-INF/components.xml) :

```xml
<bean
    id="org.sakaiproject.contentreview.service.ContentReviewService"
    class="org.sakaiproject.contentreview.impl.ContentReviewFederatedServiceImpl"
    init-method="init">
    <property name="providers" ref="contentReviewProviders"/>
    <property name="siteService" ref="org.sakaiproject.site.api.SiteService"/>
    <property name="toolManager" ref="org.sakaiproject.tool.api.ToolManager"/>
    <property name="serverConfigurationService" ref="org.sakaiproject.component.api.ServerConfigurationService" />

</bean>

<util:list id="contentReviewProviders">
    <ref bean="org.sakaiproject.contentreview.service.ContentReviewServiceUrkund"/>
</util:list>
```

