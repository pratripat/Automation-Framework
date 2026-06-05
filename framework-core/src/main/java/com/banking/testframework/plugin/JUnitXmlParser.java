package com.banking.testframework.plugin;

import com.banking.testframework.test.TestResult;
import com.banking.testframework.test.TestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses standard JUnit XML output (as produced by Playwright, Jest, Mocha, etc.)
 * into {@link TestResult} objects for unified reporting.
 */
class JUnitXmlParser {

    private static final Logger log = LoggerFactory.getLogger(JUnitXmlParser.class);

    static List<TestResult> parse(Path xmlFile, Duration totalDuration) {
        List<TestResult> results = new ArrayList<>();
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(xmlFile.toFile());
            doc.getDocumentElement().normalize();

            NodeList testCases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testCases.getLength(); i++) {
                Element tc = (Element) testCases.item(i);

                String name = tc.getAttribute("name");
                String classname = tc.getAttribute("classname");
                String id = classname.isBlank() ? "ext-" + i : classname + "." + i;
                double timeSeconds = parseDouble(tc.getAttribute("time"), 0.0);

                NodeList failures = tc.getElementsByTagName("failure");
                NodeList errors = tc.getElementsByTagName("error");
                NodeList skipped = tc.getElementsByTagName("skipped");

                TestStatus status;
                String failureMsg = null;
                String stackTrace = null;

                if (skipped.getLength() > 0) {
                    status = TestStatus.SKIPPED;
                } else if (failures.getLength() > 0) {
                    status = TestStatus.FAILED;
                    Element f = (Element) failures.item(0);
                    failureMsg = f.getAttribute("message");
                    stackTrace = f.getTextContent();
                } else if (errors.getLength() > 0) {
                    status = TestStatus.ERROR;
                    Element e = (Element) errors.item(0);
                    failureMsg = e.getAttribute("message");
                    stackTrace = e.getTextContent();
                } else {
                    status = TestStatus.PASSED;
                }

                results.add(TestResult.builder()
                        .testId(id)
                        .testName(name)
                        .suiteName("external")
                        .status(status)
                        .startTime(Instant.now())
                        .duration(Duration.ofMillis((long) (timeSeconds * 1000)))
                        .failureMessage(failureMsg)
                        .stackTrace(stackTrace)
                        .build());
            }
            log.info("Parsed {} test cases from {}", results.size(), xmlFile);
        } catch (Exception e) {
            log.error("Failed to parse JUnit XML at {}: {}", xmlFile, e.getMessage());
        }
        return results;
    }

    private static double parseDouble(String value, double defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Double.parseDouble(value); } catch (NumberFormatException e) { return defaultValue; }
    }
}
