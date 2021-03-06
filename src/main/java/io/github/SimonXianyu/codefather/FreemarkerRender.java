package io.github.SimonXianyu.codefather;

import io.github.SimonXianyu.codefather.freemarker.LowUnderMethod;
import io.github.SimonXianyu.codefather.freemarker.MyDefaultObjectWrapper;
import io.github.SimonXianyu.codefather.freemarker.Native2AsciiMethod;
import io.github.SimonXianyu.codefather.model.EntityDef;
import io.github.SimonXianyu.codefather.templates.TemplateDef;
import io.github.SimonXianyu.codefather.util.EvaluateProperties;
import io.github.SimonXianyu.codefather.util.LocalUtil;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import io.github.SimonXianyu.codefather.templates.TemplateConstants;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.mvel2.templates.TemplateRuntime;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Render using freemarker.
 * Created by simon on 2014/12/2.
 */
public class FreemarkerRender {
    private Configuration configuration;
    private Log log;

    private boolean autoMakeDirectory = true;

    private Native2AsciiMethod native2AsciiMethod = new Native2AsciiMethod();
    private LowUnderMethod lowUnderMethod = new LowUnderMethod();

    public FreemarkerRender(File templateDir, Log log) throws IOException {
        configuration = new Configuration();
        configuration.setDirectoryForTemplateLoading(templateDir);
        configuration.setDefaultEncoding("UTF-8");
        configuration.setObjectWrapper(new MyDefaultObjectWrapper());
        this.log = log;
    }

    public void render(EntityDef entityDef, TemplateDef templateDef, Properties globalProperties) throws MojoExecutionException {
        if (entityDef.isTemplateIgnored(templateDef.getFullname())) {
            if (log.isDebugEnabled()) {
                log.debug("entity "+ entityDef.getName() +" ignored template:  "+templateDef.getFullname());
            }
            return;
        }

        Map<String, Object> rootContext = createContext(null, globalProperties);
        rootContext.put("entity", entityDef);
        if (log.isDebugEnabled()) {
            log.debug("[start] process entity "+ entityDef.getName() +" with "+templateDef.getFullname());
        }

        renderByTemplate(rootContext, templateDef);
        if (log.isDebugEnabled()) {
            log.debug("[end] process entity "+ entityDef.getName());
        }
    }

    private void renderByTemplate(Map<String, Object> rootContext, TemplateDef templateDef)
            throws MojoExecutionException {
        Map<String, Object> localContext = createContext(rootContext, templateDef.getConfig());
        String  ignoreThis = LocalUtil.extractStringValue(localContext, TemplateConstants.IGNORE_THIS);
        if (Boolean.valueOf(ignoreThis)) {
            return;
        }

        String outputFilePath = (String) localContext.get(TemplateConstants.OUTPUT_PATH);
        if (outputFilePath == null) {
            throw new MojoExecutionException("Failed to get outputPath for "+templateDef.getFullname());
        }
        String outputFilename = (String) localContext.get(TemplateConstants.OUTPUT_FILENAME);
        if (outputFilename == null) {
            throw new MojoExecutionException("Failed to get outputFilename: for "+templateDef.getFullname());
        }
        File outputFileDir = new File(outputFilePath);
        if (!outputFileDir.exists()) {
            if (autoMakeDirectory) {
                if (!outputFileDir.mkdirs()) {
                    throw new MojoExecutionException("Failed to create directory "+outputFilePath);
                }
            } else {
                throw new MojoExecutionException("Target directory doesn't exists "+outputFilePath+" for "+templateDef.getFullname());
            }
        }
        File outputFile = new File(outputFileDir, outputFilename);
        if (outputFile.exists()) {
            if (!Boolean.valueOf(LocalUtil.extractStringValue(localContext, TemplateConstants.OVERWRITE, "true"))) {
                return;
            }
            if (Boolean.valueOf(LocalUtil.extractStringValue(localContext, TemplateConstants.EXTEND, "true")) ) {
                // read file and get user defined content
                StringBuilder strb = new StringBuilder();
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new FileReader(outputFile));
                    String line ;
                    boolean flag = false;
                    while ((line = br.readLine())!=null) {
                        if (line.contains("[end]User defined")) {
                            flag=false;
                            break;
                        }
                        if (flag) {
                            strb.append(line);
                        }
                        if (line.contains("[start]User defined")) {
                            flag = true;
                        }
                    }
                    if (flag) {
                        throw new RuntimeException("Failed to find end tag");
                    }
                    localContext.put("userDefinedContent", strb.toString());
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    LocalUtil.closeQuietly(br);
                }
            }
        }


        renderToFile(localContext, templateDef.getLocationName(), outputFile);
    }

    public void renderToFile(Map<String,Object> data,String templateName, File outputFile)
            throws MojoExecutionException {
        String charset = (String) data.get("charset");
        Template template;

        try {
            if (charset != null) {
                template = configuration.getTemplate(templateName, charset);
            } else {
                template = configuration.getTemplate(templateName);
            }
        } catch (IOException e1) {
            e1.printStackTrace();
            throw new MojoExecutionException("Failed to load template:" + templateName);
        }
        Writer w = null;
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(outputFile);
            if (charset != null) {
                w = new OutputStreamWriter(os, charset);
            } else {
                w = new OutputStreamWriter(os);
            }
            template.process(data, w);
            w.flush();
        } catch (TemplateException e) {
            throw new MojoExecutionException("Failed to process template:" + templateName);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to render file "+outputFile.getName());
        } finally {
            LocalUtil.closeQuietly(w);
            LocalUtil.closeQuietly(os);
        }
    }

    private Map<String, Object> createContext(Map<String, Object> upperContext, Properties config) {
        Map<String, Object> resultContext = new HashMap<String, Object>();
        resultContext.put("n2a", native2AsciiMethod);
        resultContext.put("l_u", lowUnderMethod);
        if (upperContext!=null && upperContext.size()>0) {
            resultContext.putAll(upperContext);
        }
        for(Map.Entry<Object, Object> entry : config.entrySet()) {
            String rawValue = entry.getValue().toString();
            try {
                Object value = TemplateRuntime.eval(rawValue, resultContext);
                resultContext.put(entry.getKey().toString(), value);
            } catch (Exception e) {
                log.error("Failed to process value "+ rawValue +" with " +e.getMessage());
            }
        }

        return resultContext;
    }

    public void renderGroup(List<EntityDef> entityDefList, List<TemplateDef> templateList, EvaluateProperties globalProperties) throws MojoExecutionException {
        Map<String, Object> root = createContext(null, globalProperties);
        root.put("entityList", entityDefList);

        for(TemplateDef templateDef : templateList) {
            if (log.isDebugEnabled()) {
                log.debug("[start] process group template "+templateDef.getFullname());
            }
            renderByTemplate(root, templateDef);
            if (log.isDebugEnabled()) {
                log.debug("[end] process group template "+templateDef.getFullname());
            }
        }
    }
}
