package org.yamcs.web;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import javax.activation.MimetypesFileTypeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;


public class StaticFileHandler extends RouteHandler {
    static MimetypesFileTypeMap mimeTypesMap;
    public static final int HTTP_CACHE_SECONDS = 60;
    private static WebConfig webConfig;

    private static final Logger log = LoggerFactory.getLogger(StaticFileHandler.class.getName());

    public static void init() throws ConfigurationException {
        if(mimeTypesMap!=null) return;

        try(InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("mime.types")) {
            if(is==null) {
                throw new ConfigurationException("Cannot find the mime.types file in the classpath");
            }
            mimeTypesMap = new MimetypesFileTypeMap(is);
            webConfig = WebConfig.getInstance();
        } catch (IOException e) {
            log.error("Error when closing the stream", e);
        }
    }

    void handleStaticFileRequest(ChannelHandlerContext ctx, HttpRequest req, String rawPath) throws IOException  {
        log.debug("Handling static file request for {}", rawPath);
        String path = sanitizePath(rawPath);
        if (path == null) {
            HttpRequestHandler.sendPlainTextError(ctx, req, FORBIDDEN);
            return;
        }

        File file = null;
        boolean match = false;
        for (String webRoot : webConfig.getWebRoots()) { // Stop on first match
            file = new File(webRoot + File.separator + path);
            if (!file.isHidden() && file.exists()) {
                match = true;
                break;
            }
        }

        if (!match) {
            log.warn("File {} does not exist or is hidden. Searched under {}", path, webConfig.getWebRoots());
            HttpRequestHandler.sendPlainTextError(ctx, req, NOT_FOUND);
            return;
        }
        if (!file.isFile()) {
            HttpRequestHandler.sendPlainTextError(ctx, req, FORBIDDEN);
            return;
        }

        // Cache Validation
        String ifModifiedSince = req.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE);
        if (ifModifiedSince != null && !ifModifiedSince.equals("")) {
            SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT);
            Date ifModifiedSinceDate;
            try {
                ifModifiedSinceDate = dateFormatter.parse(ifModifiedSince);
                // Only compare up to the second because the datetime format we send to the client does not have milliseconds
                long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;
                long fileLastModifiedSeconds = file.lastModified() / 1000;
                if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                    sendNotModified(ctx, req);
                    return;
                }
            } catch (ParseException e) {
                log.debug("Cannot parse {} header'{}'", HttpHeaderNames.IF_MODIFIED_SINCE, ifModifiedSince);
            }
        }

        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException ignore) {
            HttpRequestHandler.sendPlainTextError(ctx, req, NOT_FOUND);
            return;
        }
        long fileLength = raf.length();

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        HttpUtil.setContentLength(response, fileLength);
        setContentTypeHeader(response, file);
        setDateAndCacheHeaders(response, file);
        if (HttpUtil.isKeepAlive(req)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        HttpUtil.setTransferEncodingChunked(response, true);
        // Write the initial line and the header.
        ctx.writeAndFlush(response);

        // Write the content.
        ChannelFuture sendFileFuture;
        ChannelFuture lastContentFuture;
        if (webConfig.isZeroCopyEnabled() && ctx.pipeline().get(SslHandler.class) == null) {
            sendFileFuture = ctx.writeAndFlush(new DefaultFileRegion(raf.getChannel(), 0, fileLength), ctx.newProgressivePromise());
            // Write the end marker.
            lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } else {
            sendFileFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),  ctx.newProgressivePromise());
            lastContentFuture = sendFileFuture;
        }

        final File finalFile = file;
        sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
            @Override
            public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
                System.out.println("progress: "+progress);
                if (log.isTraceEnabled()) {
                    if (total < 0) { // total unknown
                        log.trace(future.channel() + " Transfer progress: " + progress);
                    } else {
                        log.trace(future.channel() + " Transfer progress: " + progress + " / " + total);
                    }
                }
            }

            @Override
            public void operationComplete(ChannelProgressiveFuture future) {
                if (log.isDebugEnabled()) {
                    log.debug(future.channel() + " Transfer complete: " +finalFile);
                }
            }
        });

        log.info("{} {} 200", req.method(), req.uri());
        if (!HttpUtil.isKeepAlive(req)) {
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }
    

    /**
     * Sets the content type header for the HTTP Response
     *
     * @param file
     *            file to extract content type
     */
    private static void setContentTypeHeader(HttpResponse response, File file) {
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeTypesMap.getContentType(file.getPath()));
    }

    /**
     * Sets the Date and Cache headers for the HTTP Response
     *
     * @param fileToCache
     *            file to extract content type
     */
    private static void setDateAndCacheHeaders(HttpResponse response, File fileToCache) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        // Date header
        Calendar time = new GregorianCalendar();
        response.headers().set(HttpHeaderNames.DATE, dateFormatter.format(time.getTime()));

        // Add cache headers
        time.add(Calendar.SECOND, HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaderNames.EXPIRES, dateFormatter.format(time.getTime()));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaderNames.LAST_MODIFIED, dateFormatter.format(new Date(fileToCache.lastModified())));
    }


    /**
     * When file timestamp is the same as what the browser is sending up, send a "304 Not Modified"
     */
    private void sendNotModified(ChannelHandlerContext ctx, HttpRequest req) {
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
        setDateHeader(response);
        log.info("{} {} 304", req.method(), req.uri());
        // Close the connection as soon as the error message is sent.
        ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }


    private String sanitizePath(String path) {
        path = path.replace('/', File.separatorChar);
        if (path.contains(File.separator + ".") ||
                path.contains("." + File.separator) ||
                path.startsWith(".") || path.endsWith(".")) {
            return null;
        }
        return path;
    }
}
