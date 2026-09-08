/**
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wso2.openbanking.demo.service;

import com.wso2.openbanking.demo.constants.OpenBankingConstants;
import com.wso2.openbanking.demo.devconsole.FlowRecorder;
import com.wso2.openbanking.demo.exceptions.SSLContextCreationException;
import com.wso2.openbanking.demo.http.AuthUrlBuilder;
import com.wso2.openbanking.demo.utils.ConfigLoader;
import com.wso2.openbanking.demo.utils.JwtUtils;
import com.wso2.openbanking.demo.utils.PkceUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.servlet.http.HttpSession;

/**
 * Starts a Rich Authorization Request. The requested access is described by
 * {@code authorization_details} (RFC 9396), pushed to the authorization server's PAR endpoint
 * (RFC 9126), so no consent resource is initiated over the API beforehand.
 */
public final class OAuthTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthTokenService.class);

    private static final String CLIENT_ASSERTION_TYPE =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String FIELD_REQUEST_URI = "request_uri";
    private static final String STEP_REDIRECT = "Authorization Redirect";

    private final HttpTlsClient client;
    private final JwtTokenService jwtTokenService;

    /**
     * Creates an OAuthTokenService using the given TLS client.
     *
     * @param client TLS HTTP client for making API calls
     * @throws GeneralSecurityException    if JWT service initialization fails
     * @throws IOException                 if the signing key cannot be read
     * @throws SSLContextCreationException if the TLS client copy fails
     */
    public OAuthTokenService(HttpTlsClient client)
            throws GeneralSecurityException, IOException, SSLContextCreationException {
        this.client = client.deepCopy();
        this.jwtTokenService = JwtTokenService.getInstance();
    }

    /**
     * Pushes an authorization request describing the given access and returns the URL the
     * customer is redirected to in order to authorize it.
     *
     * @param authorizationDetails RFC 9396 authorization details describing the requested access
     * @param session              browser session the authorization belongs to
     * @return authorization redirect URL string
     * @throws GeneralSecurityException if request object or client assertion signing fails
     * @throws IOException              if the PAR endpoint cannot be reached
     */
    public String authorize(JSONArray authorizationDetails, HttpSession session)
            throws GeneralSecurityException, IOException {
        String codeVerifier = PkceUtils.generateCodeVerifier();
        AuthFlowState.storeCodeVerifier(session, codeVerifier);
        String codeChallenge = PkceUtils.deriveCodeChallenge(codeVerifier);

        String requestObject = jwtTokenService.createRequestObject(
                authorizationDetails, codeChallenge, JwtUtils.generateNonce());
        String requestUri = pushAuthorizationRequest(requestObject, authorizationDetails, codeChallenge);

        String authorizeUrl = AuthUrlBuilder.buildWithRequestUri(requestUri, ConfigLoader.getClientId());
        // The browser makes this hop, not this application, so record it here rather than in the
        // HTTP layer. Its decoded payload is the request object that was pushed, which is where
        // the access the customer is about to authorize is actually described.
        FlowRecorder.recordHop(STEP_REDIRECT, "GET", authorizeUrl, null, requestObject);
        return authorizeUrl;
    }

    /**
     * Pushes the authorization request to the PAR endpoint and returns the resulting request URI.
     *
     * @param requestObject        signed request object JWT for the authorization request
     * @param authorizationDetails authorization details, sent alongside the request object
     * @param codeChallenge        PKCE code challenge for the authorization request
     * @return request URI issued by the PAR endpoint
     * @throws GeneralSecurityException if client assertion signing fails
     * @throws IOException              if the PAR endpoint cannot be reached
     */
    private String pushAuthorizationRequest(String requestObject, JSONArray authorizationDetails,
                                            String codeChallenge)
            throws GeneralSecurityException, IOException {
        String clientAssertion = jwtTokenService.createClientAssertion(JwtUtils.generateJti());
        String body = buildParRequestBody(requestObject, authorizationDetails, codeChallenge, clientAssertion);
        String response = client.postPushedAuthorizationRequest(ConfigLoader.getParUrl(), body);
        String requestUri = new JSONObject(response).getString(FIELD_REQUEST_URI);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Pushed authorization request accepted, request_uri obtained.");
        }
        return requestUri;
    }

    /**
     * Builds the URL-encoded body for a pushed authorization request. The signed request object
     * carries the authorization parameters; the client is authenticated with a private key JWT.
     *
     * @param requestObject        signed request object JWT
     * @param authorizationDetails authorization details describing the requested access
     * @param codeChallenge        PKCE code challenge for the authorization request
     * @param clientAssertion      signed JWT used as the client credential
     * @return URL-encoded PAR request body string
     */
    private String buildParRequestBody(String requestObject, JSONArray authorizationDetails,
                                       String codeChallenge, String clientAssertion) {
        return "client_id=" + encode(ConfigLoader.getClientId())
                + "&request=" + encode(requestObject)
                + "&" + OpenBankingConstants.FIELD_AUTHORIZATION_DETAILS
                + "=" + encode(authorizationDetails.toString())
                + "&code_challenge=" + encode(codeChallenge)
                + "&code_challenge_method=" + PkceUtils.CODE_CHALLENGE_METHOD
                + "&client_assertion_type=" + encode(CLIENT_ASSERTION_TYPE)
                + "&client_assertion=" + encode(clientAssertion);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
