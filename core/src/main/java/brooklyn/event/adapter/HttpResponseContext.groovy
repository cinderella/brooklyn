package brooklyn.event.adapter;

import groovy.json.JsonSlurper

import java.util.Map

import javax.annotation.Nullable

import org.slf4j.Logger
import org.slf4j.LoggerFactory


/** context object for evaluating sensor closures with http data handy */
public class HttpResponseContext extends AbstractSensorEvaluationContext {
	
	public static final Logger log = LoggerFactory.getLogger(HttpResponseContext.class);
	
	/** may be null during testing */
	@Nullable
	final HttpURLConnection connection;
	
	/** http result code, or -1 if error is set */
	final int responseCode;
	/** http return headers, where all values are lists (as per HttpConnection) see "headers" for direct access to non-list headers */
	final Map<String,List<String>> headerLists;
	
	/** may be null if no content available */
	@Nullable
	final String content;

	/** usual constructor */	
	public HttpResponseContext(HttpURLConnection conn) {
		this(conn, conn.getResponseCode(), conn.getHeaderFields(), getContentOrNull(conn), null)
	}
	/** constructor for when there is an error; note that many of the methods on connection will throw errors */
	public HttpResponseContext(HttpURLConnection conn, Exception error) {
		this(conn, -1, [:], null, error)
	}
	/** constructor for testing of non-connection usage */
	public HttpResponseContext(HttpURLConnection conn, int responseCode, Map headers, String content, Exception error) {
		this.connection = conn;
		this.responseCode = responseCode;
		this.headerLists = headers;
		this.content = content;
		this.error = error;
	}

    private static String getContentOrNull(HttpURLConnection conn) {
        try {
            return conn.getContent().readLines().join("\n");
        } catch (FileNotFoundException e) {
            // Happens a lot with things like 404, so just log at trace and let the rest of the response indicate what's wrong
            log.trace("Content not available for HTTP connection "+conn, e);
            return null;
        } catch (IOException e) {
            log.trace("Content not available for HTTP connection "+conn, e);
            return null;
        }
    } 
    
	protected Object getDefaultValue() { return content }
	
	private transient Map<String,Object> headers = null
	/** http return headers; values are strings in most cases, lists of strings if the original was a list with zero or 2+ values */
	public synchronized Map<String,String> getHeaders() {
		if (headers==null) {
			headers = [:]
			headerLists.each { k,v -> headers.put(k, v.size()==1 ? v.get(0) : v) }
		}
		return headers
	}
	private transient Object json;
	public synchronized Object getJson() {
		if (json==null) {
            if (content!=null && !content.isEmpty()) json = new JsonSlurper().parseText(content);
            else json = new JsonSlurper()
		}
		return json
	}
	
}
