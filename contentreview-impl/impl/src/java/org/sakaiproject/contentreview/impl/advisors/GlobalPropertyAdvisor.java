package org.sakaiproject.contentreview.impl.advisors;

import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor;

import org.sakaiproject.site.api.Site;

public class GlobalPropertyAdvisor implements ContentReviewSiteAdvisor {

	private String sakaiProperty;
	public void setSakaiProperty(String p){
		sakaiProperty = p;
	}
	
	private ServerConfigurationService serverConfigurationService;
	public void setServerConfigurationService(ServerConfigurationService s){
		serverConfigurationService = s;
	}
	
	public boolean siteCanUseReviewService(Site site) {
		return serverConfigurationService.getBoolean(sakaiProperty, false);
	}

}
