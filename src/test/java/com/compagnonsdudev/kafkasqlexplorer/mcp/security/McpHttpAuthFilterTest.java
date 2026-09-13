package com.compagnonsdudev.kafkasqlexplorer.mcp.security;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach; import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest; import org.springframework.mock.web.MockHttpServletResponse; import org.springframework.mock.web.MockFilterChain;
import static org.junit.jupiter.api.Assertions.*;
class McpHttpAuthFilterTest {
 private static final String TOKEN="test-secret-token"; private final McpHttpAuthFilter filter=new McpHttpAuthFilter(TOKEN);
 @AfterEach void clear(){McpCallerContext.clear();}
 @Test void missingTokenIsRejected() throws Exception {var q=req("/mcp","POST");q.setSecure(true);var r=new MockHttpServletResponse();filter.doFilter(q,r,new MockFilterChain());assertEquals(401,r.getStatus());}
 @Test void cleartextIsRejectedBeforeCredentialProcessing() throws Exception {var q=req("/mcp","POST");q.addHeader("Authorization","Bearer "+TOKEN);var r=new MockHttpServletResponse();var c=new Recording();filter.doFilter(q,r,c);assertEquals(426,r.getStatus());assertFalse(c.called);}
 @Test void explicitDevelopmentModeMayUseCleartext() throws Exception {var q=req("/mcp","POST");q.addHeader("Authorization","Bearer "+TOKEN);var r=new MockHttpServletResponse();var c=new Recording();new McpHttpAuthFilter(TOKEN,false).doFilter(q,r,c);assertTrue(c.called);}
 @Test void validBearerOverTlsIsAccepted() throws Exception {var q=req("/mcp","POST");q.setSecure(true);q.addHeader("Authorization","Bearer "+TOKEN);var r=new MockHttpServletResponse();var c=new Recording();filter.doFilter(q,r,c);assertTrue(c.called);assertEquals(McpHttpAuthFilter.identityOf(TOKEN),McpCallerContext.identity());}
 @Test void privilegedEndpointsAreProtected() throws Exception {assertProtected(req("/api/mcp/try/kex_list_topics","POST"));assertProtected(req("/api/mcp/approve/kex_create_metric","POST"));assertProtected(req("/api/mcp/calls/replay","GET"));}
 private void assertProtected(MockHttpServletRequest q)throws Exception{q.setSecure(true);var r=new MockHttpServletResponse();var c=new Recording();filter.doFilter(q,r,c);assertEquals(401,r.getStatus());assertFalse(c.called);}
 private static MockHttpServletRequest req(String p,String m){var q=new MockHttpServletRequest(m,p);q.setContextPath("");return q;}
 private static final class Recording extends MockFilterChain{boolean called;@Override public void doFilter(jakarta.servlet.ServletRequest q,jakarta.servlet.ServletResponse r){called=true;}}
}
