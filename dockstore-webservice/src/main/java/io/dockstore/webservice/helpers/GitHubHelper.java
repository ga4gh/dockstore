/*
 *    Copyright 2018 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package io.dockstore.webservice.helpers;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Calendar;
import java.util.Date;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.util.PemReader;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.LicenseInformation;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpStatus;
import org.kohsuke.github.GHLicense;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.webservice.Constants.LAMBDA_FAILURE;
import static io.dockstore.webservice.resources.TokenResource.HTTP_TRANSPORT;
import static io.dockstore.webservice.resources.TokenResource.JSON_FACTORY;

public final class GitHubHelper {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubHelper.class);

    private GitHubHelper() {
    }

    public static String getGitHubAccessToken(String code, String githubClientID, String githubClientSecret) {
        final AuthorizationCodeFlow flow = new AuthorizationCodeFlow.Builder(BearerToken.authorizationHeaderAccessMethod(),
                HTTP_TRANSPORT, JSON_FACTORY, new GenericUrl("https://github.com/login/oauth/access_token"),
                new ClientParametersAuthentication(githubClientID, githubClientSecret), githubClientID,
                "https://github.com/login/oauth/authorize").build();
        try {
            TokenResponse tokenResponse = flow.newTokenRequest(code)
                    .setRequestInitializer(request -> request.getHeaders().setAccept("application/json")).execute();
            if (tokenResponse.getAccessToken() != null) {
                return tokenResponse.getAccessToken();
            } else {
                LOG.error("Retrieving accessToken was unsuccessful");
                throw new CustomWebApplicationException("Could not retrieve github.com token", HttpStatus.SC_BAD_REQUEST);
            }
        } catch (IOException e) {
            LOG.error("Retrieving accessToken was unsuccessful");
            throw new CustomWebApplicationException("Could not retrieve github.com token based on code", HttpStatus.SC_BAD_REQUEST);
        }

    }

    /**
     * Get license for a specific GitHub repository
     * @param gitHub    The GitHub API
     * @param repositoryName    Name of the GitHub repository (e.g. dockstore/lambda)
     * @return  The LicenseInformation associated with the repository
     */
    public static LicenseInformation getLicenseInformation(GitHub gitHub, String repositoryName) {
        try {
            GHRepository repository = gitHub.getRepository(repositoryName);
            GHLicense license = repository.getLicense();
            if (license == null) {
                return new LicenseInformation();
            }
            LicenseInformation licenseInformation = new LicenseInformation();
            licenseInformation.setLicenseName(license.getName());
            return licenseInformation;
        } catch (IOException e) {
            LOG.info("Could not get license information from GitHub for repository: " + repositoryName, e);
            return new LicenseInformation();
        }
    }


    /**
     * Refresh the JWT for GitHub apps
     * @param gitHubAppId
     * @param gitHubPrivateKeyFile
     */
    public static void checkJWT(String gitHubAppId, String gitHubPrivateKeyFile) {
        RSAPrivateKey rsaPrivateKey = null;
        System.out.println("working dir=" + Paths.get("").toAbsolutePath().toString());
        try {
            String pemFileContent = FileUtils
                    .readFileToString(new File(gitHubPrivateKeyFile), StandardCharsets.UTF_8);
            final PemReader.Section privateKey = PemReader.readFirstSectionAndClose(new StringReader(pemFileContent), "PRIVATE KEY");
            if (privateKey != null) {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(privateKey.getBase64DecodedBytes());
                rsaPrivateKey = (RSAPrivateKey)keyFactory.generatePrivate(pkcs8EncodedKeySpec);
            } else {
                LOG.error("No private key found in " + gitHubPrivateKeyFile);
            }
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException ex) {
            LOG.error(ex.getMessage(), ex);
        }

        if (rsaPrivateKey != null) {
            final int tenMinutes = 600000;
            try {
                Algorithm algorithm = Algorithm.RSA256(null, rsaPrivateKey);
                String jsonWebToken = JWT.create()
                        .withIssuer(gitHubAppId)
                        .withIssuedAt(new Date())
                        .withExpiresAt(new Date(Calendar.getInstance().getTimeInMillis() + tenMinutes))
                        .sign(algorithm);
                CacheConfigManager.setJsonWebToken(jsonWebToken);
            } catch (JWTCreationException ex) {
                LOG.error(ex.getMessage(), ex);
            }
        }
    }

    /**
     * Based on GitHub username, find the corresponding user
     * @param tokenDAO
     * @param userDAO
     * @param username GitHub username
     * @param allowFail If true, throw a failure if user cannot be found
     * @return user with given GitHub username
     */
    public static User findUserByGitHubUsername(TokenDAO tokenDAO, UserDAO userDAO, String username, boolean allowFail) {
        // Find user by github name
        Token userGitHubToken = tokenDAO.findTokenByGitHubUsername(username);
        if (userGitHubToken == null) {
            String msg = "No user with GitHub username " + username + " exists on Dockstore.";
            LOG.info(msg);
            if (allowFail) {
                throw new CustomWebApplicationException(msg, LAMBDA_FAILURE);
            } else {
                return null;
            }
        }

        // Get user object for github token
        User sendingUser = userDAO.findById(userGitHubToken.getUserId());
        if (sendingUser == null) {
            String msg = "No user with GitHub username " + username + " exists on Dockstore.";
            LOG.info(msg);
            if (allowFail) {
                throw new CustomWebApplicationException(msg, LAMBDA_FAILURE);
            }
        }

        return sendingUser;
    }
}
