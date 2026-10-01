package spike.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Question 4: does the authorization server run on this Spring Boot / Java 25 combination, and does it
 * give us machine tokens (client credentials, decision D10) with real revocation (RFC 7009)?
 */
@SpringBootTest(classes = SpikeApplication.class)
@AutoConfigureMockMvc
class AuthorizationServerOnJava25Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void clientCredentialsTokenCanBeIssuedIntrospectedAndRevoked() throws Exception {
        String body = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(AuthorizationServerSpikeConfig.CLIENT_ID,
                                AuthorizationServerSpikeConfig.CLIENT_SECRET))
                        .param("grant_type", "client_credentials")
                        .param("scope", "records.read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(org.hamcrest.Matchers.lessThanOrEqualTo(300)))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");

        mockMvc.perform(post("/oauth2/introspect")
                        .with(httpBasic(AuthorizationServerSpikeConfig.CLIENT_ID,
                                AuthorizationServerSpikeConfig.CLIENT_SECRET))
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(post("/oauth2/revoke")
                        .with(httpBasic(AuthorizationServerSpikeConfig.CLIENT_ID,
                                AuthorizationServerSpikeConfig.CLIENT_SECRET))
                        .param("token", token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/oauth2/introspect")
                        .with(httpBasic(AuthorizationServerSpikeConfig.CLIENT_ID,
                                AuthorizationServerSpikeConfig.CLIENT_SECRET))
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void wrongClientSecretIsRejected() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(AuthorizationServerSpikeConfig.CLIENT_ID, "wrong"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }
}
