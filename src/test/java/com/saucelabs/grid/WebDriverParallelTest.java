package com.saucelabs.grid;

/**
 * @author Ross Rowe
 */

import com.saucelabs.common.SauceOnDemandAuthentication;
import com.saucelabs.junit.Parallelized;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.openqa.selenium.Platform;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.URL;
import java.util.LinkedList;

import static org.junit.Assert.assertEquals;

/**
 * Demonstrates how to write a JUnit test that runs tests against Sauce OnDemand in parallel.
 *
 * @author Ross Rowe
 */
@RunWith(Parallelized.class)
public class WebDriverParallelTest {

    public SauceOnDemandAuthentication authentication = new SauceOnDemandAuthentication();

    private String browser;
    private String os;
    private String version;

    public WebDriverParallelTest(String os, String version, String browser) {
        super();
        this.os = os;
        this.version = version;
        this.browser = browser;
    }

    @Parameterized.Parameters
    public static LinkedList browsersStrings() throws Exception {
        LinkedList browsers = new LinkedList();
        browsers.add(new String[]{Platform.WINDOWS.toString(), "16", "firefox"});
        browsers.add(new String[]{Platform.WINDOWS.toString(), "22.0.1229.94.", "chrome"});
        browsers.add(new String[]{Platform.MAC.toString(), null, "firefox"});
        //browsers.add(new String[]{Platform.WINDOWS.toString(), "10", "internet explorer"});
        return browsers;
    }

    private WebDriver driver;

    @Before
    public void setUp() throws Exception {

        DesiredCapabilities capabillities = new DesiredCapabilities();
        capabillities.setCapability(CapabilityType.BROWSER_NAME, browser);
        if (version != null)
            capabillities.setCapability(CapabilityType.VERSION, version);
        capabillities.setCapability(CapabilityType.PLATFORM, Platform.valueOf(os));

        this.driver = new RemoteWebDriver(
                new URL("http://localhost:4444/wd/hub"),
                capabillities);
    }

    @Test
    public void webDriver() throws Exception {
        System.out.println("Running: " + os + " " + browser + " " + version);
        driver.get("http://www.amazon.com/");
        assertEquals("Amazon.com: Online Shopping for Electronics, Apparel, Computers, Books, DVDs & more", driver.getTitle());
    }

    @After
    public void tearDown() throws Exception {
        driver.quit();
    }
}