package org.sakaiproject.contentreview.logic;

import org.junit.Assert;
import org.junit.Test;

import org.sakaiproject.contentreview.impl.urkund.UrkundReviewServiceImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractTransactionalJUnit4SpringContextTests;

@ContextConfiguration({"/hibernate-test.xml", "/spring-hibernate.xml"})
public class UrkundTest extends AbstractTransactionalJUnit4SpringContextTests {

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
