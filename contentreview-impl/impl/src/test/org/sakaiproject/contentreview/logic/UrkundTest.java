package org.sakaiproject.contentreview.logic;

import org.junit.Assert;
import org.junit.Test;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.contentreview.impl.urkund.UrkundReviewServiceImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractTransactionalJUnit4SpringContextTests;

@ContextConfiguration({"/hibernate-test.xml", "/spring-hibernate.xml"})
public class UrkundTest extends AbstractTransactionalJUnit4SpringContextTests {
	private static final Log log = LogFactory.getLog(UrkundTest.class);

	@Test
	public void testFileEscape() {
		UrkundReviewServiceImpl urkundService = new UrkundReviewServiceImpl();
		String someEscaping = urkundService.escapeFileName("Practical%203.docx", "contentId");
		Assert.assertEquals("Practical_3.docx", someEscaping);
		
		someEscaping = urkundService.escapeFileName("Practical%203%.docx", "contentId");
		Assert.assertEquals("contentId", someEscaping);
		
		someEscaping = urkundService.escapeFileName("Practical3.docx", "contentId");
		Assert.assertEquals("Practical3.docx", someEscaping);
		
		
	}
	
	
}
