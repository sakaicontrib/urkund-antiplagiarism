/**********************************************************************************
 * $URL: $
 * $Id: SiteCourseTypeAdvisor.java $
 ***********************************************************************************
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
package org.sakaiproject.contentreview.impl.advisors;

import org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor;
import org.sakaiproject.site.api.Site;

public class SiteCourseTypeAdvisor implements ContentReviewSiteAdvisor {

	public boolean siteCanUseReviewService(Site site) {
		String type = site.getType();
		
		if (type != null ) {
			if (type.equals("course"))
				return true;
		}
		return false;
		
	}
}
