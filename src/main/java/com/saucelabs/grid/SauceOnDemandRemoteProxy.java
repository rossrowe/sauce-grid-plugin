package com.saucelabs.grid;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.saucelabs.grid.services.SauceOnDemandRestAPIException;
import com.saucelabs.grid.services.SauceOnDemandService;
import com.saucelabs.grid.services.SauceOnDemandServiceImpl;
import org.json.JSONException;
import org.json.JSONObject;
import org.openqa.grid.common.JSONConfigurationUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.internal.utils.CapabilityMatcher;
import org.openqa.grid.internal.utils.HtmlRenderer;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;
import org.openqa.grid.web.servlet.handler.RequestType;
import org.openqa.grid.web.servlet.handler.WebDriverRequest;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.internal.HttpClientFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * This proxy instance is designed to forward requests to Sauce OnDemand.  Requests will be forwarded to Sauce OnDemand
 * if the proxy is configured to 'failover' (ie. handle desired capabilities that are not handled by any other node in
 * the hub), or if the proxy is configured to respond to a specific desired capability.
 *
 * @author François Reynaud - Initial version of plugin
 * @author Ross Rowe - Additional functionality
 */
public class SauceOnDemandRemoteProxy extends DefaultRemoteProxy {

    private static final Logger logger = Logger.getLogger(SauceOnDemandRemoteProxy.class.getName());
    private static final SauceOnDemandService service = new SauceOnDemandServiceImpl();

    public static final String SAUCE_ONDEMAND_CONFIG_FILE = "sauce-ondemand.json";
    public static final String SAUCE_USER_NAME = "sauceUserName";
    public static final String SAUCE_ACCESS_KEY = "sauceAccessKey";
    public static final String SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES = "sauceHandleUnspecifiedCapabilities";
    public static final String SAUCE_CONNECT = "sauceConnect";
    public static final String SAUCE_ENABLE = "sauceEnable";
    public static final String SAUCE_WEB_DRIVER_CAPABILITIES = "sauceWebDriverCapabilities";
    public static final String SAUCE_RC_CAPABILITIES = "sauceSeleniumRCCapabilities";
    private static final String URL_FORMAT = "http://{0}:{1}";
    private static final String SELENIUM_HOST = "seleniumHost";
    private static final String SELENIUM_PORT = "seleniumPort";
    private static URL DEFAULT_SAUCE_CONNECT_URL;
    private static URL SAUCE_ONDEMAND_URL;

    private volatile boolean sauceAvailable = false;
    private String userName;
    private String accessKey;
    private boolean shouldProxySauceOnDemand = true;
    private boolean shouldHandleUnspecifiedCapabilities;
    private CapabilityMatcher capabilityHelper;
    private int maxSauceSessions;
    private String[] webDriverCapabilities;
    private String[] seleniumCapabilities;
    private final SauceHttpClientFactory httpClientFactory;


    static {
        try {
            SAUCE_ONDEMAND_URL = new URL("http://ondemand.saucelabs.com:80");
            InputStream inputStream = SauceOnDemandRemoteProxy.class.getResourceAsStream("/logging.properties");
            if (inputStream != null) {
                LogManager.getLogManager().readConfiguration(inputStream);
            }
        } catch (MalformedURLException e) {
            //shouldn't happen
            logger.log(Level.SEVERE, "Error constructing remote host url", e);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error constructing remote host url", e);
        }
    }

    /**
     * Defaults to ondemand.saucelabs.com
     */
    private String seleniumHost = "ondemand.saucelabs.com";
    /**
     * Defaults to 80.
     */
    private String seleniumPort = "80";

    public boolean shouldProxySauceOnDemand() {
        return shouldProxySauceOnDemand;
    }

    public SauceOnDemandRemoteProxy(RegistrationRequest req, Registry registry) {
        super(updateDesiredCapabilities(req), registry);
        httpClientFactory = new SauceHttpClientFactory(this);
        //TODO include proxy id in json file
        JsonObject sauceConfiguration = readConfigurationFromFile();
        try {
            this.userName = (String) req.getConfiguration().get(SAUCE_USER_NAME);
            this.accessKey = (String) req.getConfiguration().get(SAUCE_ACCESS_KEY);
            String configHost = (String) req.getConfiguration().get(SELENIUM_HOST);
            if (configHost != null) {
                this.seleniumHost = configHost;
            }
            String configPort = (String) req.getConfiguration().get(SELENIUM_PORT);
            if (configPort != null) {
                this.seleniumPort = configPort;
            }
            String handleUnspecifiedCapabilities = (String) req.getConfiguration().get(SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES);
            if (handleUnspecifiedCapabilities != null) {
                this.shouldHandleUnspecifiedCapabilities = Boolean.valueOf(handleUnspecifiedCapabilities);
            }

            if (userName != null && accessKey != null) {
                this.maxSauceSessions = service.getMaxiumumSessions(userName, accessKey);
                if (maxSauceSessions == -1) {
                    //this is actually infinity, but set it to 100
                    maxSauceSessions = 100;
                }
            }
            Object b = req.getConfiguration().get(SAUCE_ENABLE);
            if (b != null) {
                shouldProxySauceOnDemand = Boolean.valueOf(b.toString());
            }

            if (sauceConfiguration != null) {
                if (sauceConfiguration.has(SAUCE_WEB_DRIVER_CAPABILITIES)) {
                    JsonArray keyArray = sauceConfiguration.getAsJsonArray(SAUCE_WEB_DRIVER_CAPABILITIES);
                    this.webDriverCapabilities = new String[keyArray.size()];
                    for (int i = 0; i < keyArray.size(); i++) {
                        webDriverCapabilities[i] = keyArray.get(i).getAsString();
                    }
                }
                if (sauceConfiguration.has(SAUCE_RC_CAPABILITIES)) {
                    JsonArray keyArray = sauceConfiguration.getAsJsonArray(SAUCE_RC_CAPABILITIES);
                    this.seleniumCapabilities = new String[keyArray.size()];
                    for (int i = 0; i < keyArray.size(); i++) {
                        seleniumCapabilities[i] = keyArray.get(i).getAsString();
                    }
                }
            }
        } catch (SauceOnDemandRestAPIException e) {
            logger.log(Level.SEVERE, "Error invoking Sauce REST API", e);
        }

    }

    private static RegistrationRequest updateDesiredCapabilities(RegistrationRequest request) {
        JsonObject sauceConfiguration = readConfigurationFromFile();
        try {
            if (sauceConfiguration != null) {
                if (sauceConfiguration.has(SELENIUM_HOST)) {
                    request.getConfiguration().put(SELENIUM_HOST, sauceConfiguration.get(SELENIUM_HOST).getAsString());
                }
                if (sauceConfiguration.has(SELENIUM_PORT)) {
                    request.getConfiguration().put(SELENIUM_PORT, sauceConfiguration.get(SELENIUM_PORT).getAsString());
                }
                if (sauceConfiguration.has(SAUCE_USER_NAME)) {
                    request.getConfiguration().put(SAUCE_USER_NAME, sauceConfiguration.get(SAUCE_USER_NAME).getAsString());
                }
                if (sauceConfiguration.has(SAUCE_ACCESS_KEY)) {
                    request.getConfiguration().put(SAUCE_ACCESS_KEY, sauceConfiguration.get(SAUCE_ACCESS_KEY).getAsString());
                }
                if (sauceConfiguration.has(SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES)) {
                    request.getConfiguration().put(SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES, sauceConfiguration.get(SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES).getAsString());
                }
                if (sauceConfiguration.has(SAUCE_ENABLE)) {
                    request.getConfiguration().put(SAUCE_ENABLE, sauceConfiguration.get(SAUCE_ENABLE).getAsString());
                }

                List<SauceOnDemandCapabilities> caps = new ArrayList<SauceOnDemandCapabilities>();
                if (sauceConfiguration.has(SAUCE_WEB_DRIVER_CAPABILITIES)) {
                    request.getCapabilities().clear();
                    BrowsersCache webDriverBrowsers = new BrowsersCache(service.getWebDriverBrowsers());

                    JsonArray keyArray = sauceConfiguration.getAsJsonArray(SAUCE_WEB_DRIVER_CAPABILITIES);
                    for (JsonElement element : keyArray) {
                        SauceOnDemandCapabilities sauceOnDemandCapabilities = webDriverBrowsers.get(element.getAsString());
                        if (sauceOnDemandCapabilities != null) {
                            caps.add(sauceOnDemandCapabilities);
                        }

                    }
                }
                if (sauceConfiguration.has(SAUCE_RC_CAPABILITIES)) {
                    request.getCapabilities().clear();
                    BrowsersCache seleniumBrowsers = new BrowsersCache(service.getSeleniumBrowsers());

                    JsonArray keyArray = sauceConfiguration.getAsJsonArray(SAUCE_RC_CAPABILITIES);
                    for (JsonElement element : keyArray) {
                        SauceOnDemandCapabilities sauceOnDemandCapabilities = seleniumBrowsers.get(element.getAsString());
                        if (sauceOnDemandCapabilities != null) {
                            caps.add(sauceOnDemandCapabilities);
                        }
                    }
                }

                int maxiumumSessions = service.getMaxiumumSessions(
                        sauceConfiguration.get(SAUCE_USER_NAME).getAsString(),
                        sauceConfiguration.get(SAUCE_ACCESS_KEY).getAsString());
                if (maxiumumSessions == -1) {
                    maxiumumSessions = 20;
                }
                if (caps.isEmpty()) {
                    for (DesiredCapabilities capability : request.getCapabilities()) {
                        capability.setCapability(RegistrationRequest.MAX_INSTANCES, maxiumumSessions);
                    }
                } else {
                    for (SauceOnDemandCapabilities cap : caps) {
                        DesiredCapabilities c = new DesiredCapabilities(cap.asMap());
                        c.setCapability(RegistrationRequest.MAX_INSTANCES, maxiumumSessions);
                        request.getCapabilities().add(c);
                    }
                }
            }
        } catch (SauceOnDemandRestAPIException e) {
            logger.log(Level.SEVERE, "Error invoking Sauce REST API", e);
        }
        return request;
    }

    public static JsonObject readConfigurationFromFile() {

        File file = new File(SAUCE_ONDEMAND_CONFIG_FILE);
        if (file.exists()) {
            return JSONConfigurationUtils.loadJSON(file.getName());
        }
        return null;
    }


    @Override
    public boolean hasCapability(Map<String, Object> requestedCapability) {
        logger.log(Level.INFO, "Checking capability: " + requestedCapability);
        if (shouldHandleUnspecifiedCapabilities/* && browser combination is supported by sauce labs*/) {
            logger.log(Level.INFO, "Handling capability: " + requestedCapability);
            return true;
        }
        return super.hasCapability(requestedCapability);
    }

    /**
     * @param requestedCapability
     * @return
     */
    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {

        //if no proxy can handle requested capability, and shouldHandleUnspecifiedCapabilities is set to true
        //(and the browser capabillitiy is supported by Sauce), create new desired capability that runs
        //against sauce
        try {
            this.sauceAvailable = service.isSauceLabUp();
            if (!sauceAvailable) {
                throw new RuntimeException("Sauce OnDemand is not available");
            }
        } catch (SauceOnDemandRestAPIException e) {
            logger.log(Level.SEVERE, "Error invoking Sauce REST API", e);
        }

        if ((shouldProxySauceOnDemand && sauceAvailable) || !shouldProxySauceOnDemand) {
            logger.log(Level.INFO, "Creating new session for: " + requestedCapability);
            TestSession session = super.getNewSession(requestedCapability);
            logger.log(Level.INFO, "New session created for: " + requestedCapability);
            return session;
        } else {
            return null;
        }
    }

    @Override
    public CapabilityMatcher getCapabilityHelper() {
        if (capabilityHelper == null) {
            capabilityHelper = new SauceOnDemandCapabilityMatcher(this);
        }
        return this.capabilityHelper;

    }

    @Override
    public HtmlRenderer getHtmlRender() {
        return new SauceOnDemandRenderer(this);
    }

    /**
     * Need to ensure that Sauce proxy is handled last after all local nodes.
     *
     * @param o
     * @return
     */
    @Override
    public int compareTo(RemoteProxy o) {
        if (!(o instanceof SauceOnDemandRemoteProxy)) {
            //ensure that local nodes are listed first, so that if a local node can handle the request, it is given
            //precedence
            //TODO this should be configurable
            return 1;
        } else {
            // there should only be one sauce proxy in use, so this branch won't get executed
            return super.compareTo(o);
        }
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public void writeConfigurationToFile() {
        JSONObject jsonObject = new JSONObject();

        try {
            jsonObject.put(SAUCE_USER_NAME, getUserName());
            jsonObject.put(SAUCE_ACCESS_KEY, getAccessKey());
            jsonObject.put(SAUCE_HANDLE_UNSPECIFIED_CAPABILITIES, shouldHandleUnspecifiedCapabilities());
            jsonObject.put(SAUCE_WEB_DRIVER_CAPABILITIES, getWebDriverCapabilities());
            jsonObject.put(SELENIUM_HOST, getSeleniumHost());
            jsonObject.put(SELENIUM_PORT, getSeleniumPort());
            //TODO handle selected browsers
            FileWriter file = new FileWriter(SAUCE_ONDEMAND_CONFIG_FILE);
            file.write(jsonObject.toString());
            file.flush();
            file.close();

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error parsing JSON", e);
        } catch (JSONException e) {
            logger.log(Level.SEVERE, "Error parsing JSON", e);
        }
    }

    public boolean shouldHandleUnspecifiedCapabilities() {
        return shouldHandleUnspecifiedCapabilities;
    }

    public void setShouldHandleUnspecifiedCapabilities(boolean shouldHandleUnspecifiedCapabilities) {
        this.shouldHandleUnspecifiedCapabilities = shouldHandleUnspecifiedCapabilities;
    }

    /**
     * There isn't an easy way to return a remote host based on the specific {@link TestSlot}, so we
     * always return ondemand.saucelabs.com
     *
     * @return
     */
    public URL getRemoteHost() {
        if (seleniumHost != null && seleniumPort != null) {
            try {
                return new URL(MessageFormat.format(URL_FORMAT, seleniumHost, seleniumPort));
            } catch (MalformedURLException e) {
                e.printStackTrace();
                return SAUCE_ONDEMAND_URL;
            }
        }
        return SAUCE_ONDEMAND_URL;
    }

    public URL getNodeHost() {
        return remoteHost;
    }

    @Override
    public HttpClientFactory getHttpClientFactory() {
        return httpClientFactory;
    }

    @Override
    public void afterCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        super.afterCommand(session, request, response);
        logger.log(Level.INFO, "Finished executing " + request.toString());
    }

    @Override
    public void beforeCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {

        logger.log(Level.INFO, "About to execute " + request.toString());
        if (request instanceof WebDriverRequest && request.getMethod().equals("POST")) {
            WebDriverRequest seleniumRequest = (WebDriverRequest) request;
            if (seleniumRequest.getRequestType().equals(RequestType.START_SESSION)) {
                String body = seleniumRequest.getBody();
                //convert from String to JSON
                try {
                    JSONObject json = new JSONObject(body);
                    //add username/accessKey
                    JSONObject desiredCapabilities = json.getJSONObject("desiredCapabilities");
                    if (desiredCapabilities.has("sauce:platform")) {
                        desiredCapabilities.put("platform", desiredCapabilities.getString("sauce:platform"));
                    }
                    desiredCapabilities.put("username", this.userName);
                    desiredCapabilities.put("accessKey", this.accessKey);
                    //convert from JSON to String
                    seleniumRequest.setBody(json.toString());
                    logger.log(Level.INFO, "Updating desired capabilities : " + desiredCapabilities);
                } catch (JSONException e) {
                    logger.log(Level.SEVERE, "Error parsing JSON", e);
                }
            }

        }
        super.beforeCommand(session, request, response);
    }

    @Override
    public int getMaxNumberOfConcurrentTestSessions() {
        int result;
        if (shouldProxySauceOnDemand()) {
            result = maxSauceSessions;
        } else {
            result = super.getMaxNumberOfConcurrentTestSessions();
        }
        logger.log(Level.INFO, "Maximum concurrent sessions: " + result);
        return result;
    }

    public void setWebDriverCapabilities(String[] webDriverCapabilities) {
        this.webDriverCapabilities = webDriverCapabilities;
    }

    public String[] getWebDriverCapabilities() {
        return webDriverCapabilities;
    }

    public void setSeleniumCapabilities(String[] seleniumCapabilities) {
        this.seleniumCapabilities = seleniumCapabilities;
    }

    public boolean isWebDriverBrowserSelected(SauceOnDemandCapabilities cap) {
        if (webDriverCapabilities != null) {
            for (String md5 : webDriverCapabilities) {
                if (md5.equals(cap.getMD5())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int getTotalUsed() {
        int totalUsed = super.getTotalUsed();
        logger.log(Level.INFO, "Total Slots Used: " + totalUsed);
        return totalUsed;
    }

    @Override
    public boolean isBusy() {
        boolean result = super.isBusy();
        logger.log(Level.INFO, "Proxy isBusy: " + result);
        return result;
    }

    public String getSeleniumHost() {
        return seleniumHost;
    }

    public String getSeleniumPort() {
        return seleniumPort;
    }

    public void setSeleniumHost(String seleniumHost) {
        this.seleniumHost = seleniumHost;
    }

    public void setSeleniumPort(String seleniumPort) {
        this.seleniumPort = seleniumPort;
    }
}
