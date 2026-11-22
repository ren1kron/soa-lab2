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
    void rejectsClientSuppliedEmployeeCount() throws Exception {
        for (var request :
                new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                    post("/organizations"), put("/organizations/1")
                }) {
            mvc.perform(
                            request.contentType("application/xml")
                                    .content(
                                            "<organization><name>org</name><coordinates><x>1</x><y>2</y></coordinates><employeeCount>5</employeeCount></organization>"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(
                            xpath("/error/details/detail[field='employeeCount']/code")
                                    .string("READ_ONLY_FIELD"));
        }
    }

    @Test
    void rejectsUnexpectedBodiesAndBadIds() throws Exception {
        mvc.perform(get("/organizations").content("x")).andExpect(status().isBadRequest());
        mvc.perform(get("/employees/0")).andExpect(status().isBadRequest());
    }

    @Test
    void reportsUnsupportedFilterOperatorAndUnexpectedBodyTogether() throws Exception {
        mvc.perform(get("/organizations").param("filter", "name:gt:abc").content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(xpath("/error/code").string("INVALID_REQUEST"))
                .andExpect(
                        xpath("/error/details/detail[field='filter[0]']/code")
                                .string("UNKNOWN_OPERATOR"))
                .andExpect(
                        xpath("/error/details/detail[field='$']/code").string("UNEXPECTED_BODY"));
    }

    @Test
    void reportsTheDocumentedContentEncodingError() throws Exception {
        mvc.perform(
                        post("/employees")
                                .contentType("application/xml")
                                .header("Content-Encoding", "gzip")
                                .content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(
                        xpath("/error/message")
                                .string("Кодирование тела запроса не поддерживается"))
                .andExpect(
                        xpath("/error/details/detail[field='Content-Encoding']/code")
                                .string("UNSUPPORTED_ENCODING"));
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
    void readsAcceptAndContentEncodingAcrossHeaderLines() throws Exception {
        mvc.perform(get("/organizations").header("Accept", "*/*;q=1", "application/xml;q=0"))
                .andExpect(status().isNotAcceptable())
                .andExpect(xpath("/error/details/detail/field").string("Accept"));
        mvc.perform(
                        post("/employees")
                                .contentType("application/xml")
                                .header("Content-Encoding", "identity", "gzip")
                                .content("<employee><name>ok</name></employee>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(xpath("/error/details/detail/code").string("UNSUPPORTED_ENCODING"));
    }

    @Test
    void preservesRoutingAndMethodErrorsAsClientErrors() throws Exception {
        mvc.perform(get("/unknown")).andExpect(status().isNotFound());
        mvc.perform(get("/organizations/1/unknown")).andExpect(status().isNotFound());
        mvc.perform(patch("/organizations/1")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void returnsTheContractErrorForAnUnexpectedFailure() throws Exception {
        mvc.perform(get("/organizations/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(xpath("/error/code").string("INTERNAL_ERROR"))
                .andExpect(
                        xpath("/error/message")
                                .string(
                                        "Не удалось завершить запрос. Если вы изменяли данные, проверьте, применились ли изменения"))
                .andExpect(xpath("count(/error/details)").number(1.0))
                .andExpect(xpath("count(/error/details/*)").number(0.0));
    }

    @Test
    void returnsWellFormedXmlWhenAnInvalidParameterContainsAControlCharacter() throws Exception {
        mvc.perform(get("/organizations").param("bad\u0001", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(xpath("/error/details/detail/field").string("bad�"));
    }
}
