package com.seatsurge.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.seatsurge.TestcontainersConfiguration;

/** Base for full-stack tests: real Postgres + Redis via Testcontainers, shared Spring context. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    /** Registers a fresh user with the given self-assignable role and returns its access token. */
    protected String tokenFor(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String body = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"s3cure-pass","fullName":"Test %s","role":"%s"}
                                """.formatted(email, role, role)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    protected ResultActions getAs(String token, String url, Object... vars) throws Exception {
        return mockMvc.perform(withAuth(get(url, vars), token));
    }

    protected ResultActions postAs(String token, String url, String json, Object... vars) throws Exception {
        MockHttpServletRequestBuilder req = post(url, vars);
        if (json != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(withAuth(req, token));
    }

    protected ResultActions putAs(String token, String url, String json, Object... vars) throws Exception {
        return mockMvc.perform(withAuth(put(url, vars).contentType(MediaType.APPLICATION_JSON).content(json), token));
    }

    protected ResultActions deleteAs(String token, String url, Object... vars) throws Exception {
        return mockMvc.perform(withAuth(delete(url, vars), token));
    }

    protected static <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), jsonPath);
    }

    private static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder req, String token) {
        return token == null ? req : req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
