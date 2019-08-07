package com.proxeus;

import com.proxeus.document.FileResult;
import com.proxeus.document.TemplateCompiler;
import com.proxeus.error.BadRequestException;
import com.proxeus.error.CompilationException;
import com.proxeus.error.NotImplementedException;
import com.proxeus.error.UnavailableException;
import com.proxeus.office.libre.LibreConfig;
import com.proxeus.office.libre.LibreOfficeAssistant;
import com.proxeus.office.libre.exe.Extension;
import com.proxeus.office.libre.exe.LibreOfficeFormat;
import com.proxeus.util.Json;
import com.proxeus.util.zip.Zip;

import org.apache.commons.fileupload.MultipartStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.log4j.Logger;
import org.eclipse.jetty.io.EofException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import spark.Response;

import static com.proxeus.Application.config;
import static spark.Spark.get;
import static spark.Spark.ipAddress;
import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.staticFiles;
import static spark.Spark.threadPool;

/**
 * SparkServer defines the protocol of this services.
 */
public class SparkServer {
    private LibreOfficeAssistant libreOfficeAssistant;
    private TemplateCompiler templateCompiler;

    private final Charset defaultCharset = StandardCharsets.UTF_8;
    private final String JSON_CONTENT_TYPE = "application/json; charset=UTF-8";
    private final String TEXT_CONTENT_TYPE = "text/plain; charset=UTF-8";

    public SparkServer(Config config) {
        removeTheDiskCacheOfDocs();
        Logger log = Logger.getLogger(this.getClass());
        threadPool(config.max, config.min, config.timeoutMillis);
        port(config.getPort());
        ipAddress(config.getHost());
        try {
            libreOfficeAssistant = new LibreOfficeAssistant(Config.by(LibreConfig.class));
            templateCompiler = new TemplateCompiler(config.getTmpFolder(), libreOfficeAssistant);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        staticFiles.location("/public");
        get("/api", (request, response) -> {
            api(response);
            return 0;
        });
        get("/", (request, response) -> {
            api(response);
            return 0;
        });
        get("/how-it-works", (request, response) -> {
            try{
                boolean inline = request.queryMap().hasKey("inline");
                response.raw().setContentType(LibreOfficeFormat.PDF.contentType);
                response.raw().setHeader("Content-Disposition", (inline?"inline":"attachment")+"; filename=\"how.it.works.pdf\"");
                streamAndClose(getODTAsPDFFromResources(config, "how.it.works.odt"), response.raw().getOutputStream());
            }catch (Exception e){
                notFound(response);
            }
            return 0;
        });
        get("/example", (request, response) -> {
            try{
                InputStream is;
                LibreOfficeFormat format;
                if(request.queryMap().hasKey("raw")){
                    format = LibreOfficeFormat.ODT;
                    is = SparkServer.class.getResourceAsStream("/example/tmpl.odt");
                }else{
                    format = LibreOfficeFormat.PDF;
                    is = getDirAsPDFFromResources(config, "example");
                }
                boolean inline = request.queryMap().hasKey("inline");
                response.raw().setContentType(format.contentType);
                response.raw().setHeader("Content-Disposition", (inline?"inline":"attachment")+"; filename=\"example."+format.ext+"\"");
                streamAndClose(is, response.raw().getOutputStream());
            }catch (Exception e){
                notFound(response);
            }
            return 0;
        });
        post("/compile", (request, response) -> {
            try {
                StopWatch sw = StopWatch.createStarted();
                FileResult result = templateCompiler.compile(request.raw().getInputStream(), request.queryParams("format"), request.queryMap().hasKey("error"));
                response.header("Content-Type", result.contentType);
                response.header("Content-Length", "" + result.target.length());
                try {
                    streamAndClose(new FileInputStream(result.target), response.raw().getOutputStream());
                } finally {
                    result.release();
                }
                System.out.println("request took: " + sw.getTime(TimeUnit.MILLISECONDS));
            } catch(EofException | MultipartStream.MalformedStreamException eof){
                try{
                    response.raw().getOutputStream().close();
                }catch (Exception idc){}
            } catch (CompilationException e) {
                error(422, response, e);
            } catch (BadRequestException e) {
                error(HttpURLConnection.HTTP_BAD_REQUEST, response, e);
            } catch (NotImplementedException e) {
                error(HttpURLConnection.HTTP_NOT_IMPLEMENTED, response, e);
            } catch (UnavailableException e) {
                error(HttpURLConnection.HTTP_UNAVAILABLE, response, e);
            } catch (Exception e) {
                error(HttpURLConnection.HTTP_INTERNAL_ERROR, response, e);
            }
            return 0;
        });
        get("/extension", (request, response) -> {
            try {
                //request.queryParams("app")
                //app is meant for future releases
                //right now there is just libre so we can ignore this param
                Extension extension = libreOfficeAssistant.getExtension(request.queryParams("os"));
                response.raw().setContentType(extension.contentType);
                response.raw().setHeader("Content-Disposition", "attachment; filename=\"" + extension.fileName+"\"");
                streamAndClose(extension.inputStream, response.raw().getOutputStream());
            } catch (Exception e) {
                notFound(response);
            }
            return 0;
        });
        post("/vars", (request, response) -> {
            try {
                response.type(JSON_CONTENT_TYPE);
                OutputStream os = response.raw().getOutputStream();
                InputStream is = request.raw().getInputStream();
                os.write(Json.toJson(templateCompiler.vars(is, request.queryParams("prefix"))).getBytes(defaultCharset));
                os.flush();
                os.close();
                is.close();
            } catch(org.eclipse.jetty.io.EofException | MultipartStream.MalformedStreamException eof){
                try{
                    response.raw().getOutputStream().close();
                }catch (Exception idc){}
            } catch (CompilationException e) {
                error(422, response, e);
            } catch (BadRequestException e) {
                error(HttpURLConnection.HTTP_BAD_REQUEST, response, e);
            } catch (NotImplementedException e) {
                error(HttpURLConnection.HTTP_NOT_IMPLEMENTED, response, e);
            } catch (UnavailableException e) {
                error(HttpURLConnection.HTTP_UNAVAILABLE, response, e);
            } catch (Exception e) {
                error(HttpURLConnection.HTTP_INTERNAL_ERROR, response, e);
            }
            return 0;
        });
        spark.Spark.init();
        log.info("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<]]][ Document Service started ][[[>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
    }

    private void api(Response response){
        try{
            response.type("text/html; charset=UTF-8");
            streamAndClose(SparkServer.class.getResourceAsStream("/api.html"), response.raw().getOutputStream());
        }catch (Exception e){
            notFound(response);
        }
    }

    private InputStream getODTAsPDFFromResources(Config config, String name) throws Exception {
        File f = new File(config.getTmpFolder(), name);
        if (f.exists()) {
            return new FileInputStream(f);
        } else {
            streamAndClose(SparkServer.class.getResourceAsStream("/" + name), new FileOutputStream(f));
            libreOfficeAssistant.Convert(f, f, "pdf");
            return new FileInputStream(f);
        }
    }

    private InputStream getDirAsPDFFromResources(Config config, String name) throws Exception {
        File f = new File(config.getTmpFolder(), name);
        if (f.exists()) {
            return new FileInputStream(f);
        } else {
            File zip = Zip.resourceDir(config.getTmpFolder(), name);
            if (zip == null) {
                return null;
            }
            //compile
            FileResult result = templateCompiler.compile(new FileInputStream(zip), "pdf", false);
            Files.move(result.target.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return new FileInputStream(f);
        }
    }

    private void removeTheDiskCacheOfDocs(){
        //remove the disk cache to load it again from the jar
        new File(config.getTmpFolder(), "how.it.works.odt").delete();
        try{
            //remove the disk cache to load it again from the jar
            FileUtils.deleteDirectory(new File(config.getTmpFolder(), "example"));
        }catch (Exception e){
            //not important
        }
    }

    private void notFound(Response response) {
        try{
            response.status(HttpURLConnection.HTTP_NOT_FOUND);
            OutputStream os = response.raw().getOutputStream();
            //close the response stream to prevent spark from fooling around with the return value
            os.flush();
            os.close();
        }catch (Exception e){
            //not important
            System.err.println("couldn't send the not found response");
        }
    }

    private void streamAndClose(InputStream is, OutputStream os) throws Exception {
        IOUtils.copy(is, os);
        os.flush();
        os.close();
        is.close();
    }

    private void error(int status, Response response, Exception e) {
        try{
            response.status(status);
            response.type(TEXT_CONTENT_TYPE);
            OutputStream os = response.raw().getOutputStream();
            String msg = e.getMessage();
            if(msg == null){
                msg = "null";
            }
            os.write(msg.getBytes(defaultCharset));
            os.flush();
            os.close();
        }catch (Exception ee){
            System.err.println("couldn't send the error response");
        }
    }
}