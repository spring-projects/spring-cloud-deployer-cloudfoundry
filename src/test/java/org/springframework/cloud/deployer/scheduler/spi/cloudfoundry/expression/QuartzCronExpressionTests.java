/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.scheduler.spi.cloudfoundry.expression;

import java.text.ParseException;

import junit.framework.TestCase;

public class QuartzCronExpressionTests extends TestCase {

	/*
	 * Verifies that storeExpressionVals correctly calculates the month number
	 */
	public void testStoreExpressionVal() {
		try {
			new QuartzCronExpression("* * * * Foo ? ");
			fail("Expected ParseException did not fire for non-existent month");
		}
		catch (ParseException pe) {
			assertTrue("Incorrect ParseException thrown",
					pe.getMessage().startsWith("Invalid Month value:"));
		}

		try {
			new QuartzCronExpression("* * * * Jan-Foo ? ");
			fail("Expected ParseException did not fire for non-existent month");
		}
		catch (ParseException pe) {
			assertTrue("Incorrect ParseException thrown",
					pe.getMessage().startsWith("Invalid Month value:"));
		}
	}

	public void testWildCard() {
		try {
			new QuartzCronExpression("0 0 * * * *");
			fail("Expected ParseException did not fire for wildcard day-of-month and day-of-week");
		}
		catch (ParseException pe) {
			assertTrue("Incorrect ParseException thrown",
					pe.getMessage().startsWith("Support for specifying both a day-of-week AND a day-of-month parameter is not implemented."));
		}
		try {
			new QuartzCronExpression("0 0 * 4 * *");
			fail("Expected ParseException did not fire for specified day-of-month and wildcard day-of-week");
		}
		catch (ParseException pe) {
			assertTrue("Incorrect ParseException thrown",
					pe.getMessage().startsWith("Support for specifying both a day-of-week AND a day-of-month parameter is not implemented."));
		}
		try {
			new QuartzCronExpression("0 0 * * * 4");
			fail("Expected ParseException did not fire for wildcard day-of-month and specified day-of-week");
		}
		catch (ParseException pe) {
			assertTrue("Incorrect ParseException thrown",
					pe.getMessage().startsWith("Support for specifying both a day-of-week AND a day-of-month parameter is not implemented."));
		}
	}

	public void testForInvalidLInCronExpression() {
		try {
			new QuartzCronExpression("0 43 9 1,5,29,L * ?");
			fail("Expected ParseException did not fire for L combined with other days of the month");
		}
		catch (ParseException pe) {
			assertTrue("Incorrect ParseException thrown",
					pe.getMessage().startsWith("Support for specifying 'L' and 'LW' with other days of the month is not implemented"));
		}
		try {
			new QuartzCronExpression("0 43 9 ? * SAT,SUN,L");
			fail("Expected ParseException did not fire for L combined with other days of the week");
		}
		catch (ParseException pe) {
			assertTrue("Incorrect ParseException thrown",
					pe.getMessage().startsWith("Support for specifying 'L' with other days of the week is not implemented"));
		}
		try {
			new QuartzCronExpression("0 43 9 ? * 6,7,L");
			fail("Expected ParseException did not fire for L combined with other days of the week");
		}
		catch (ParseException pe) {
			assertTrue("Incorrect ParseException thrown",
					pe.getMessage().startsWith("Support for specifying 'L' with other days of the week is not implemented"));
		}
		try {
			new QuartzCronExpression("0 43 9 ? * 5L");
		}
		catch (ParseException pe) {
			fail("Unexpected ParseException thrown for supported '5L' expression.");
		}
	}


	public void testForLargeWVal() {
		try {
			new QuartzCronExpression("0/5 * * 32W 1 ?");
			fail("Expected ParseException did not fire for W with value larger than 31");
		}
		catch (ParseException pe) {
			assertTrue("Incorrect ParseException thrown",
					pe.getMessage().startsWith("The 'W' option does not make sense with values larger than"));
		}
	}

	public void testSecRangeIntervalAfterSlash() {
		// Test case 1
		try {
			new QuartzCronExpression("/120 0 8-18 ? * 2-6");
			fail("Cron did not validate bad range interval in '_blank/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 60 : 120");
		}

		// Test case 2
		try {
			new QuartzCronExpression("0/120 0 8-18 ? * 2-6");
			fail("Cron did not validate bad range interval in in '0/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 60 : 120");
		}

		// Test case 3
		try {
			new QuartzCronExpression("/ 0 8-18 ? * 2-6");
			fail("Cron did not validate bad range interval in '_blank/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}

		// Test case 4
		try {
			new QuartzCronExpression("0/ 0 8-18 ? * 2-6");
			fail("Cron did not validate bad range interval in '0/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}
	}

	public void testMinRangeIntervalAfterSlash() {
		// Test case 1
		try {
			new QuartzCronExpression("0 /120 8-18 ? * 2-6");
			fail("Cron did not validate bad range interval in '_blank/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 60 : 120");
		}

		// Test case 2
		try {
			new QuartzCronExpression("0 0/120 8-18 ? * 2-6");
			fail("Cron did not validate bad range interval in in '0/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 60 : 120");
		}

		// Test case 3
		try {
			new QuartzCronExpression("0 / 8-18 ? * 2-6");
			fail("Cron did not validate bad range interval in '_blank/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}

		// Test case 4
		try {
			new QuartzCronExpression("0 0/ 8-18 ? * 2-6");
			fail("Cron did not validate bad range interval in '0/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}
	}

	public void testHourRangeIntervalAfterSlash() {
		// Test case 1
		try {
			new QuartzCronExpression("0 0 /120 ? * 2-6");
			fail("Cron did not validate bad range interval in '_blank/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 24 : 120");
		}

		// Test case 2
		try {
			new QuartzCronExpression("0 0 0/120 ? * 2-6");
			fail("Cron did not validate bad range interval in in '0/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 24 : 120");
		}

		// Test case 3
		try {
			new QuartzCronExpression("0 0 / ? * 2-6");
			fail("Cron did not validate bad range interval in '_blank/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}

		// Test case 4
		try {
			new QuartzCronExpression("0 0 0/ ? * 2-6");
			fail("Cron did not validate bad range interval in '0/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}
	}

	public void testDayOfMonthRangeIntervalAfterSlash() {
		// Test case 1
		try {
			new QuartzCronExpression("0 0 0 /120 * 2-6");
			fail("Cron did not validate bad range interval in '_blank/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 31 : 120");
		}

		// Test case 2
		try {
			new QuartzCronExpression("0 0 0 0/120 * 2-6");
			fail("Cron did not validate bad range interval in in '0/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 31 : 120");
		}

		// Test case 3
		try {
			new QuartzCronExpression("0 0 0 / * 2-6");
			fail("Cron did not validate bad range interval in '_blank/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}

		// Test case 4
		try {
			new QuartzCronExpression("0 0 0 0/ * 2-6");
			fail("Cron did not validate bad range interval in '0/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}
	}

	public void testMonthRangeIntervalAfterSlash() {
		// Test case 1
		try {
			new QuartzCronExpression("0 0 0 ? /120 2-6");
			fail("Cron did not validate bad range interval in '_blank/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 12 : 120");
		}

		// Test case 2
		try {
			new QuartzCronExpression("0 0 0 ? 0/120 2-6");
			fail("Cron did not validate bad range interval in in '0/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 12 : 120");
		}

		// Test case 3
		try {
			new QuartzCronExpression("0 0 0 ? / 2-6");
			fail("Cron did not validate bad range interval in '_blank/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}

		// Test case 4
		try {
			new QuartzCronExpression("0 0 0 ? 0/ 2-6");
			fail("Cron did not validate bad range interval in '0/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}
	}


	public void testDayOfWeekRangeIntervalAfterSlash() {
		// Test case 1
		try {
			new QuartzCronExpression("0 0 0 ? * /120");
			fail("Cron did not validate bad range interval in '_blank/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 7 : 120");
		}

		// Test case 2
		try {
			new QuartzCronExpression("0 0 0 ? * 0/120");
			fail("Cron did not validate bad range interval in in '0/xxx' form");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "Increment > 7 : 120");
		}

		// Test case 3
		try {
			new QuartzCronExpression("0 0 0 ? * /");
			fail("Cron did not validate bad range interval in '_blank/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}

		// Test case 4
		try {
			new QuartzCronExpression("0 0 0 ? * 0/");
			fail("Cron did not validate bad range interval in '0/_blank'");
		}
		catch (ParseException e) {
			assertEquals(e.getMessage(), "'/' must be followed by an integer.");
		}
	}

}
