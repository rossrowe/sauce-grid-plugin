package com.saucelabs.grid;

import com.saucelabs.grid.services.SauceOnDemandRestAPIException;
import com.saucelabs.grid.services.SauceOnDemandService;
import com.saucelabs.grid.services.SauceOnDemandServiceImpl;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.Registry;
import org.openqa.selenium.remote.DesiredCapabilities;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Fran�ois Reynaud - Initial version of plugin
 * @author Ross Rowe - Additional functionality
 */
public class SauceOnDemandAdminServlet extends AbstractSauceOnDemandServlet {

    private static final String UPDATE_BROWSERS = "updateSupportedBrowsers";
    public static final String WEB_DRIVER_CAPABILITIES = "webDriverCapabilities";
    public static final String SELENIUM_CAPABILITIES = "seleniumCapabilities";
    private SauceOnDemandService service = new SauceOnDemandServiceImpl();
    private final BrowsersCache webDriverBrowsers;
    private final BrowsersCache seleniumBrowsers;
    private static final String SAUCE_USER_NAME = "sauceUserName";
    private static final String SAUCE_ACCESS_KEY = "sauceAccessKey";
    private static final String SAUCE_HANDLE_UNSPECIFIED = "sauceHandleUnspecified";

    public SauceOnDemandAdminServlet() throws SauceOnDemandRestAPIException {
        this(null);
    }


    public SauceOnDemandAdminServlet(Registry registry) throws SauceOnDemandRestAPIException {
        super(registry);
        webDriverBrowsers = new BrowsersCache(service.getWebDriverBrowsers());
        seleniumBrowsers = new BrowsersCache(service.getSeleniumBrowsers());
    }


    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
            IOException {
        String id = req.getParameter("id");
        SauceOnDemandRemoteProxy p = getProxy(id);
        if (req.getPathInfo().endsWith(UPDATE_BROWSERS)) {
            updateBrowsers(req, resp, p);
            resp.sendRedirect("/grid/console");
        }
    }

    @Override
    protected void renderFooter(HttpServletRequest request, StringBuilder builder) {

    }

    @Override
    protected void renderBody(HttpServletRequest request, StringBuilder builder) {
        String id = request.getParameter("id");
        SauceOnDemandRemoteProxy p = getProxy(id);
        builder.append("<form action='/grid/admin/SauceOnDemandAdminServlet/").append(UPDATE_BROWSERS).append("' method='POST'>");
        builder.append("<div class='proxy'>");
        builder.append("<fieldset>");
        builder.append("<legend class='proxyname' accesskey=c>Sauce OnDemand Configuration</legend>");
        builder.append("<div>");
        builder.append("<label for=\"maxSessions\">Max parallel sessions</label> : <input type='text' name='").
                append(RegistrationRequest.MAX_SESSION).
                append("' id='").append(RegistrationRequest.MAX_SESSION).
                append("' value='").append(p.getMaxNumberOfConcurrentTestSessions()).
                append("' />");
        builder.append("</div>");
        builder.append("<div>");
        builder.append("<label for='").append(SAUCE_USER_NAME).append("'>User Name</label> : <input type='text' name='").
                append(SAUCE_USER_NAME).
                append("' id='").append(SAUCE_USER_NAME).
                append("' value='").append(p.getUserName()).
                append("' />");
        builder.append("</div>");
        builder.append("<div>");
        builder.append("<label for='").append(SAUCE_ACCESS_KEY).append("'>Access Key</label> : <input type='text' name='").
                append(SAUCE_ACCESS_KEY).append("' id='").append(SAUCE_ACCESS_KEY).
                append("' size='50' value='").append(p.getAccessKey()).append("' />");
        builder.append("</div>");
        builder.append("<div>");
        builder.append("<label for='").append(SAUCE_HANDLE_UNSPECIFIED).append("'>Handle All Unspecified Capabilities?</label>");
        builder.append("<input type='checkbox' name='").append(SAUCE_HANDLE_UNSPECIFIED).
                append("' id='").append(SAUCE_HANDLE_UNSPECIFIED).append("'");
        if (p.shouldHandleUnspecifiedCapabilities()) {
            builder.append(" checked='checked' ");
        }
        builder.append("value='Handle All Unspecified Capabilities?'/>");
        builder.append("</div>");
        builder.append("</fieldset>");
        builder.append("</div>");

        builder.append("<div class='proxy'>");
        builder.append("<fieldset>");
        builder.append("<legend class='proxyname' accesskey=c>Sauce OnDemand Browsers (WebDriver)</legend>");
        builder.append("<select name='").append(WEB_DRIVER_CAPABILITIES).append("' multiple='multiple'>");
        for (SauceOnDemandCapabilities cap : webDriverBrowsers.getAllBrowsers()) {

            builder.append("<option value='").append(cap.getMD5()).append("'>");
            builder.append(cap);
            builder.append("</option>");
        }
        builder.append("</select>");
        builder.append("</fieldset>");
        builder.append("</div>");

        builder.append("<div class='proxy'>");
        builder.append("<fieldset>");
        builder.append("<legend class='proxyname' accesskey=c>Sauce OnDemand Browsers (Selenium RC)</legend>");
        builder.append("<select name='").append(SELENIUM_CAPABILITIES).append("' multiple='multiple'>");
        for (SauceOnDemandCapabilities cap : seleniumBrowsers.getAllBrowsers()) {

            builder.append("<option value='").append(cap.getMD5()).append("'>");
            builder.append(cap);
            builder.append("</option>");
        }
        builder.append("</select>");
        builder.append("</fieldset>");
        builder.append("</div>");

        builder.append("<input type='hidden' name='id' value='").append(p.getId()).append("' />");
        builder.append("<input type='submit' value='save' />");

        builder.append("</form>");
    }


    private void updateBrowsers(HttpServletRequest req, HttpServletResponse resp,
                                SauceOnDemandRemoteProxy proxy) {
        String[] webDriverCapabilities = req.getParameterValues(WEB_DRIVER_CAPABILITIES);
        List<SauceOnDemandCapabilities> caps = new ArrayList<SauceOnDemandCapabilities>();
        if (webDriverCapabilities != null) {
            for (String md5 : webDriverCapabilities) {
                caps.add(webDriverBrowsers.get(md5));
            }
        }
        String[] seleniumRCCapabilities = req.getParameterValues(SELENIUM_CAPABILITIES);
        if (seleniumRCCapabilities != null) {
            for (String md5 : seleniumRCCapabilities) {
                caps.add(seleniumBrowsers.get(md5));
            }
        }
        getRegistry().removeIfPresent(proxy);

        String userName = req.getParameter(SAUCE_USER_NAME);
        String accessKey = req.getParameter(SAUCE_ACCESS_KEY);
        String max = req.getParameter(RegistrationRequest.MAX_SESSION);
        boolean handleUnspecified = req.getParameter(SAUCE_HANDLE_UNSPECIFIED) != null || !(req.getParameter(SAUCE_HANDLE_UNSPECIFIED).equals(""));
        int m = Integer.parseInt(max);

        RegistrationRequest sauceRequest = proxy.getOriginalRegistrationRequest();
        // re-create the test slots with the new capabilities.
        sauceRequest.getCapabilities().clear();
        sauceRequest.getConfiguration().put(RegistrationRequest.MAX_SESSION, m);
        for (SauceOnDemandCapabilities cap : caps) {
            DesiredCapabilities c = new DesiredCapabilities(cap.asMap());
            c.setCapability(RegistrationRequest.MAX_INSTANCES, m);
            sauceRequest.getCapabilities().add(c);
        }

        //write selected browsers/auth details to sauce-ondemand.json
        proxy.setUserName(userName);
        proxy.setAccessKey(accessKey);
        proxy.setWebDriverCapabilities(webDriverCapabilities);
        proxy.setSeleniumCapabilities(seleniumRCCapabilities);
        proxy.setShouldHandleUnspecifiedCapabilities(handleUnspecified);
        proxy.writeConfigurationToFile();
        SauceOnDemandRemoteProxy newProxy = new SauceOnDemandRemoteProxy(sauceRequest, getRegistry());
        getRegistry().add(newProxy);
    }

    private SauceOnDemandRemoteProxy getProxy(String id) {
        return (SauceOnDemandRemoteProxy) getRegistry().getProxyById(id);
    }


}