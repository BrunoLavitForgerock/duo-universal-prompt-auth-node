package edu.northwestern.adminSystems.duoNode;

import com.duosecurity.Client;
import com.duosecurity.exception.DuoException;
import com.duosecurity.model.Token;
import com.google.inject.assistedinject.Assisted;
import com.iplanet.am.util.SystemProperties;
import com.sun.identity.authentication.spi.RedirectCallback;
import com.sun.identity.shared.Constants;
import org.forgerock.json.JsonValue;
import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.AbstractDecisionNode;
import org.forgerock.openam.auth.node.api.*;
import org.forgerock.openam.core.CoreWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;

@Node.Metadata(
        outcomeProvider = AbstractDecisionNode.OutcomeProvider.class,
        configClass = DuoNode.Config.class, tags = {"mfa", "multi-factor authentication"}
)
public class DuoNode extends AbstractDecisionNode {
    private final Logger logger = LoggerFactory.getLogger("amAuth");

    private Client duoClient;
    private String clientId;
    private String clientSecret;
    private String apiHostName;
    private String callbackUri;
    private String failureMode;

    @Inject
    public DuoNode(@Assisted Config config, CoreWrapper coreWrapper) throws NodeProcessException {
        clientId = config.clientId();
        clientSecret = config.clientSecret();
        apiHostName = config.apiHostName();
        callbackUri = config.callbackUri();
        failureMode = config.failureMode();
        duoClient = initializeDuoClient();

        // @TODO remove me once this is shown to work. but may be helpful for the 1st time we try this node out.
        logger.debug(
            "Initialized Duo node with the following config: clientID = {}, secret = {} characters, API hostname = {}, callback URI = {}, and failure mode = {}",
            clientId,
            clientSecret.length(),
            apiHostName,
            callbackUri,
            failureMode
        );
    }

    @Override
    public Action process(TreeContext context) throws NodeProcessException {
        Map<String, List<String>> parameters = context.request.parameters;
        JsonValue sharedState = context.sharedState;
        String userReference = sharedState.get(SharedStateConstants.USERNAME).asString().toLowerCase();

        try {
            duoClient.healthCheck();
        } catch (DuoException e) {
            if (failureMode.equals(Config.FailureModes.CLOSED)) {
                throw new NodeProcessException("Duo health check failed. Cannot proceed.", e);
            } else {
                logger.error("Duo health check failed, but failure mode is set to open. Bypassing Duo.");
                return goTo(true).build();
            }
        }

        if (parameters.containsKey(DuoConstants.RESP_DUO_CODE) && parameters.containsKey(DuoConstants.RESP_STATE))
        {
            if (! sharedState.isDefined(DuoConstants.SESSION_STATE)) {
                // This shouldn't happen. It means either someone is doing shenanigans, or something has gone awry with the session.
                // Clear the session out & try again.
                logger.warn("Detected Duo callback without initialized session. This may be a spoofing attempt (or a timed out session).");

                sharedState.remove(DuoConstants.SESSION_STATE);
            } else if (! sharedState.get(DuoConstants.SESSION_STATE).toString().equals(parameters.get(DuoConstants.RESP_STATE))) {
                // This also shouldn't happen -- the state we stored & the state we got back from the redirect URL param ought to match.
                // Clear the session out & try again.
                logger.warn("Detected Duo callback with invalid session. This may be a spoofing attempt (or a timed out session).");

                sharedState.remove(DuoConstants.SESSION_STATE);
            } else {
                String duoCode = parameters.get(DuoConstants.RESP_DUO_CODE).toString();
                String state = sharedState.get(DuoConstants.SESSION_STATE).toString();

                Boolean authenticated = validateDuoAuthenticated(duoCode, state);
                logger.debug("Duo validation result: {}", authenticated); // @TODO remove this line, it'll be spammy. but useful during dev.

                return goTo(authenticated).build();
            }
        }

        String state = duoClient.generateState();
        sharedState.put(DuoConstants.SESSION_STATE, state);

        String duoUrl = createDuoAuthUrl(userReference, state);

        RedirectCallback redirectCallback = new RedirectCallback(duoUrl, null, "GET");
        redirectCallback.setTrackingCookie(true);
        
        return Action.send(redirectCallback).build();
    }

    private Client initializeDuoClient() throws NodeProcessException {
        Client.Builder duoClient = new Client.Builder(clientId, clientSecret, apiHostName, callbackUri);

        try {
            return duoClient.build();
        } catch (DuoException e) {
            throw new NodeProcessException("Could not initialize Duo client. This probably indicates invalid configuration for the auth node.", e);
        }
    }

    private String createDuoAuthUrl(String userReference, String state) throws NodeProcessException {
        try {
            return duoClient.createAuthUrl(userReference, state);
        } catch (DuoException e) {
            throw new NodeProcessException("Unable to create Duo authentication URL", e);
        }
    }

    private Boolean validateDuoAuthenticated(String duoCode, String state) throws NodeProcessException {
        try {
            Token token = duoClient.exchangeAuthorizationCodeFor2FAResult(duoCode, state);

            if (token == null || token.getAuth_result() == null) {
                return false;
            }

            return DuoConstants.DUO_TOKEN_SUCCESSFUL_RESULT.equalsIgnoreCase(token.getAuth_result().getStatus());
        } catch (DuoException e) {
            throw new NodeProcessException("Unable to exchange authorization code for result", e);
        }
    }

    /**
     * Configuration for the Duo node.
     */
    public interface Config {
        enum FailureModes {
            CLOSED,
            OPEN,
        }

        @Attribute(name = "Duo Client ID (ikey)", order = 100)
        default String clientId() {
            return "";
        }

        @Attribute(name = "Duo Client Secret (skey)", order = 200)
        default String clientSecret() {
            return "";
        }

        @Attribute(name = "Duo API Hostname", order = 300)
        default String apiHostName() {
            return "";
        }

        @Attribute(name = "Failure Mode When Duo is Down", order = 400) // @TODO add choiceValuesClass key & restrict this to CLOSED/OPEN
        default String failureMode() {
            return FailureModes.CLOSED.toString();
        }

        @Attribute(name = "OpenAM Callback URL", order = 500)
        default String callbackUri() {
            final String protocol = SystemProperties.get(Constants.AM_SERVER_PROTOCOL);
            final String host = SystemProperties.get(Constants.AM_SERVER_HOST);
            final String port = SystemProperties.get(Constants.AM_SERVER_PORT);
            final String descriptor = SystemProperties.get(Constants.AM_SERVICES_DEPLOYMENT_DESCRIPTOR);

            if (protocol != null && host != null && port != null && descriptor != null) {
                return protocol + "://" + host + ":" + port + descriptor;
            } else {
                return "";
            }
        }

    }

}
