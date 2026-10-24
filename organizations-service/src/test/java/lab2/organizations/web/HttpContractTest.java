package lab2.organizations.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import lab2.organizations.web.controller.*;
import lab2.organizations.web.error.ApiExceptionHandler;
import lab2.organizations.web.validation.*;
import lab2.organizations.web.xml.XmlMessageConverter;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HttpContractTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new OrganizationsController(null, new CollectionQueryResolver()),
                                new EmployeesController(null, new CollectionQueryResolver()))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .setMessageConverters(new XmlMessageConverter())
                        .addFilters(new RequestGuard())
                        .build();
    }

    @Test
    void rejectsRepeatedAndUnknownQueriesBeforeCallingController() throws Exception {
        mvc.perform(get("/organizations").param("sort", "name", "id"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/xml"));
        mvc.perform(get("/employees").param("unexpected", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void exposesXmlValidationDetails() throws Exception {
        mvc.perform(
                        post("/organizations")
                                .contentType("application/xml")
                                .content(
                                        "<organization><name/><coordinates><y>1</y></coordinates></organization>"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(
                        xpath("/error/details/detail[field='coordinates.x']/code")
                                .string("REQUIRED"));
    }

    @Test
    void rejectsUnexpectedBodiesAndBadIds() throws Exception {
        mvc.perform(get("/organizations").content("x")).andExpect(status().isBadRequest());
        mvc.perform(get("/employees/0")).andExpect(status().isBadRequest());
    }

    @Test
    void enforcesAcceptContentTypeAndContentEncoding() throws Exception {
        mvc.perform(get("/organizations").accept("application/json"))
                .andExpect(status().isNotAcceptable());
        mvc.perform(get("/organizations").header("Accept", "application/xml;q=0,*/*;q=1"))
                .andExpect(status().isNotAcceptable());
        mvc.perform(post("/employees").contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(
                        post("/employees")
                                .contentType("application/xml")
                                .header("Content-Encoding", "gzip")
                                .content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void preservesRoutingAndMethodErrorsAsClientErrors() throws Exception {
        mvc.perform(get("/unknown")).andExpect(status().isNotFound());
        mvc.perform(get("/organizations/1/unknown")).andExpect(status().isNotFound());
        mvc.perform(patch("/organizations/1")).andExpect(status().isMethodNotAllowed());
    }
}
