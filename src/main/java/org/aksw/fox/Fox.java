package org.aksw.fox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.aksw.fox.data.Entity;
import org.aksw.fox.data.Relation;
import org.aksw.fox.data.TokenManager;
import org.aksw.fox.data.exception.LoadingNotPossibleException;
import org.aksw.fox.data.exception.UnsupportedLangException;
import org.aksw.fox.tools.ner.INER;
import org.aksw.fox.tools.ner.Tools;
import org.aksw.fox.tools.ner.ToolsGenerator;
import org.aksw.fox.tools.ner.linking.ILinking;
import org.aksw.fox.tools.ner.linking.NoLinking;
import org.aksw.fox.tools.re.FoxRETools;
import org.aksw.fox.tools.re.IRE;
import org.aksw.fox.utils.FoxCfg;
import org.aksw.fox.utils.FoxConst;
import org.aksw.fox.utils.FoxJena;
import org.aksw.fox.utils.FoxTextUtil;
import org.aksw.fox.utils.FoxWebLog;
import org.apache.jena.riot.Lang;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.jetlang.fibers.Fiber;
import org.jetlang.fibers.ThreadFiber;

/**
 * An implementation of {@link org.aksw.fox.IFox}.
 * 
 * @author rspeck
 * 
 */
public class Fox implements IFox {

    public static final String  parameter_input    = "input";

    public static final String  parameter_task     = "task";
    public static final String  parameter_output   = "output";
    public static final String  parameter_foxlight = "foxlight";
    public static final String  parameter_nif      = "nif";
    public static final String  parameter_type     = "type";
    public static final String  parameter_disamb   = "disamb";

    public static final Logger  LOG                = LogManager.getLogger(Fox.class);

    private String              lang;

    /**
     * 
     */
    protected ILinking          linking            = null;

    /**
     * 
     */

    protected Tools             nerTools           = null;
    protected FoxRETools        reTools            = new FoxRETools();

    /**
     * 
     */
    // protected TokenManager tokenManager = null;

    /**
     * 
     */
    protected FoxJena           foxJena            = new FoxJena();

    /**
     * Holds a tool for fox's light version.
     */
    // protected INER nerLight = null;

    /**
     * 
     */
    protected FoxWebLog         foxWebLog          = null;

    private CountDownLatch      countDownLatch     = null;
    private Map<String, String> parameter          = null;
    private String              response           = null;

    @SuppressWarnings("unused")
    private Fox() {
    }

    /*
     * 
     */
    public Fox(String lang) {
        this.lang = lang;

        try {
            ToolsGenerator toolsGenerator = new ToolsGenerator();
            linking = toolsGenerator.getDisambiguationTool(lang);
            nerTools = toolsGenerator.getNERTools(lang);
        } catch (UnsupportedLangException | LoadingNotPossibleException e) {
            LOG.error(e.getLocalizedMessage(), e);
        }
        LOG.info("linking: " + linking.getClass());

        // TODO: relation extraction
        // reTools = new FoxRETools();
    }

    protected Set<Entity> doNER() {
        infoLog("Start NER (" + lang + ")...");
        Set<Entity> entities = nerTools.getEntities(parameter.get(parameter_input));

        // remove duplicate annotations
        Map<String, Entity> wordEntityMap = new HashMap<>();
        for (Entity entity : entities) {
            if (wordEntityMap.get(entity.getText()) == null) {
                wordEntityMap.put(entity.getText(), entity);
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("We have a duplicate annotation and removing those: "
                            + entity.getText() + " "
                            + entity.getType() + " "
                            + wordEntityMap.get(entity.getText()).getType());
                }
                wordEntityMap.remove(entity.getText());
            }
        }
        // remove
        entities.retainAll(wordEntityMap.values());
        infoLog("NER done.");
        return entities;
    }

    protected Set<Relation> doRE() {
        Set<Relation> relations = null;
        IRE reTool = reTools.getRETool(lang);
        if (reTool == null)
            infoLog("Relation tool for " + lang.toUpperCase() + " not supported yet.");
        else {
            infoLog("Start RE ...");
            final CountDownLatch latch = new CountDownLatch(1);
            reTool.setCountDownLatch(latch);
            reTool.setInput(parameter.get(parameter_input));

            Fiber fiber = new ThreadFiber();
            fiber.start();
            fiber.execute(reTool);

            int min = Integer.parseInt(FoxCfg.get(Tools.CFG_KEY_LIFETIME));
            try {
                latch.await(min, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                LOG.error(e.getLocalizedMessage(), e);
            }
            // shutdown threads
            fiber.dispose();

            // get results
            if (latch.getCount() == 0) {
                relations = reTool.getResults();
            } else {
                infoLog("Timeout after " + min + " min.");
            }
            infoLog("RE done.");
        }
        return relations;
    }

    protected Set<Entity> doNERLight(String name) {
        infoLog("Starting light NER version ...");
        Set<Entity> entities = new HashSet<>();
        if (name == null || name.isEmpty())
            return entities;

        INER nerLight = null;
        for (INER t : nerTools.getNerTools()) {
            if (name.equals(t.getClass().getName()))
                nerLight = t;
        }
        /*
        // loads a tool
        try {
            nerLight = (INER) FoxCfg.getClass(name);
        } catch (LoadingNotPossibleException e) {
            LOG.error(e.getLocalizedMessage(), e);
            return entities;
        }
        */
        if (nerLight == null) {
            LOG.info("Given (" + name + ") tool is not supported.");
            return entities;
        }

        infoLog("NER tool(" + lang + ") is: " + nerLight.getToolName());

        // clean input
        String input = parameter.get(parameter_input);
        TokenManager tokenManager = new TokenManager(input);
        input = tokenManager.getInput();
        parameter.put(parameter_input, input);

        {
            final CountDownLatch latch = new CountDownLatch(1);
            Fiber fiber = new ThreadFiber();
            nerLight.setCountDownLatch(latch);
            nerLight.setInput(parameter.get(parameter_input));

            fiber.start();
            fiber.execute(nerLight);

            int min = Integer.parseInt(FoxCfg.get(Tools.CFG_KEY_LIFETIME));
            try {
                latch.await(min, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                LOG.error("Timeout after " + min + " min.");
                LOG.error("\n", e);
                LOG.error("input:\n" + parameter.get(parameter_input));
            }

            // shutdown threads
            fiber.dispose();
            // get results
            if (latch.getCount() == 0) {
                entities = new HashSet<Entity>(nerLight.getResults());

            } else {
                if (LOG.isDebugEnabled())
                    LOG.debug("timeout after " + min + "min.");
                // TODO: handle timeout
            }
        }

        tokenManager.repairEntities(entities);

        // make an index for each entity
        Map<Integer, Entity> indexMap = new HashMap<>();
        for (Entity entity : entities)
            for (Integer i : FoxTextUtil.getIndices(entity.getText(), tokenManager.getTokenInput()))
                indexMap.put(i, entity);

        // sort
        List<Integer> sortedIndices = new ArrayList<>(indexMap.keySet());
        Collections.sort(sortedIndices);

        // loop index in sorted order
        int offset = -1;
        for (Integer i : sortedIndices) {
            Entity e = indexMap.get(i);
            if (offset < i) {
                offset = i + e.getText().length();
                e.addIndicies(i);
            }
        }

        // remove entity without an index
        {
            Set<Entity> cleanEntity = new HashSet<>();
            for (Entity e : entities) {
                if (e.getIndices() != null && e.getIndices().size() > 0) {
                    cleanEntity.add(e);
                }
            }
            entities = cleanEntity;
        }

        nerLight = null;
        infoLog("Light version done.");
        return entities;
    }

    protected void setURIs(Set<Entity> entities) {
        if (entities != null && !entities.isEmpty()) {
            infoLog("Start NE linking ...");

            final CountDownLatch latch = new CountDownLatch(1);
            linking.setCountDownLatch(latch);
            linking.setInput(entities, parameter.get(parameter_input));

            Fiber fiber = new ThreadFiber();
            fiber.start();
            fiber.execute(linking);

            // use another time for the uri lookup?
            int min = Integer.parseInt(FoxCfg.get(Tools.CFG_KEY_LIFETIME));
            try {
                latch.await(min, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                LOG.error("Timeout after " + min + "min.");
                LOG.error("\n", e);
            }

            // shutdown threads
            fiber.dispose();
            // get results
            if (latch.getCount() == 0) {
                entities = new HashSet<Entity>(linking.getResults());
            } else {
                infoLog("Timeout after " + min + " min (" + linking.getClass().getName() + ").");
                // use dev lookup after timeout
                new NoLinking().setUris(entities, parameter.get(parameter_input));
            }
            infoLog("Start NE linking done.");
        }
    }

    protected void setOutput(Set<Entity> entities, Set<Relation> relations) {
        if (entities == null) {
            LOG.warn("Entities are empty.");
            return;
        }

        String input = parameter.get(parameter_input);

        // switch output
        final boolean useNIF = Boolean.parseBoolean(parameter.get(parameter_nif));

        String out = parameter.get(parameter_output);
        infoLog("Preparing output format ...");

        foxJena.clearGraph();
        foxJena.setAnnotations(entities);

        if (relations != null) {
            foxJena.setRelations(relations);
            infoLog("Found " + relations.size() + " relations.");
        }

        response = foxJena.print(out, useNIF, input);
        infoLog("Preparing output format done.");

        if (parameter.get("returnHtml") != null && parameter.get("returnHtml").toLowerCase().endsWith("true")) {

            Map<Integer, Entity> indexEntityMap = new HashMap<>();
            for (Entity entity : entities) {
                for (Integer startIndex : entity.getIndices()) {
                    // TODO : check contains
                    indexEntityMap.put(startIndex, entity);
                }
            }

            Set<Integer> startIndices = new TreeSet<>(indexEntityMap.keySet());

            String html = "";

            int last = 0;
            for (Integer index : startIndices) {
                Entity entity = indexEntityMap.get(index);
                if (entity.uri != null && !entity.uri.trim().isEmpty()) {
                    html += input.substring(last, index);
                    html += "<a class=\"" + entity.getType().toLowerCase() + "\" href=\"" + entity.uri + "\"  target=\"_blank\"  title=\"" + entity.getType().toLowerCase() + "\" >" + entity.getText() + "</a>";
                    last = index + entity.getText().length();
                } else {
                    LOG.error("Entity has no URI: " + entity.getText());
                }
            }

            html += input.substring(last);
            parameter.put(parameter_input, html);

            if (LOG.isTraceEnabled())
                infotrace(entities);
        }
        infoLog("Found " + entities.size() + " entities.");
    }

    /**
     * 
     */
    @Override
    public void run() {
        foxWebLog = new FoxWebLog();
        infoLog("Running Fox...");

        if (parameter == null) {
            LOG.error("Parameter not set.");
        } else {
            String input = parameter.get(parameter_input);
            String task = parameter.get(parameter_task);
            String light = parameter.get(parameter_foxlight);

            Set<Entity> entities = null;
            Set<Relation> relations = null;

            if (input == null || task == null) {
                LOG.error("Input or task parameter not set.");
            } else {
                TokenManager tokenManager = new TokenManager(input);
                // clean input
                input = tokenManager.getInput();
                parameter.put(parameter_input, input);

                // light version
                if (light != null && !light.equals("OFF")) {
                    // switch task
                    switch (task.toLowerCase()) {

                    case "ke":
                        // TODO: ke fox
                        LOG.info("Starting light KE version ...");
                        break;

                    case "ner":
                        entities = doNERLight(light);
                        break;
                    }

                } else {
                    // no light version
                    switch (task.toLowerCase()) {
                    case "ke":
                        // TODO: ke fox
                        LOG.info("starting ke ...");
                        break;

                    case "ner":
                        entities = doNER();
                        break;

                    case "re":
                        entities = doNER();
                        relations = doRE();
                        break;
                    }
                }
            }

            setURIs(entities);
            setOutput(entities, relations);
        }

        // done
        infoLog("Running Fox done.");
        if (countDownLatch != null)
            countDownLatch.countDown();
    }

    /**
     * Prints debug infos about entities for each tool and final entities in
     * fox.
     * 
     * @param entities
     *            final entities
     */
    private void infotrace(Set<Entity> entities) {
        if (LOG.isTraceEnabled()) {

            LOG.trace("entities:");
            for (String toolname : nerTools.getToolResult().keySet()) {
                if (nerTools.getToolResult().get(toolname) == null)
                    return;

                LOG.trace(toolname + ": " + nerTools.getToolResult().get(toolname).size());
                for (Entity e : nerTools.getToolResult().get(toolname))
                    LOG.trace(e);
            }

            LOG.trace("fox" + ": " + entities.size());
            for (Entity e : entities)
                LOG.trace(e);
        }
    }

    @Override
    public void setCountDownLatch(CountDownLatch cdl) {
        this.countDownLatch = cdl;
    }

    @Override
    public void setParameter(Map<String, String> parameter) {
        this.parameter = parameter;

        String paraUriLookup = parameter.get(parameter_disamb);
        if (paraUriLookup != null) {
            if (paraUriLookup.equalsIgnoreCase("off"))
                paraUriLookup = NoLinking.class.getName();

            try {
                linking = (ILinking) FoxCfg.getClass(paraUriLookup.trim());
            } catch (Exception e) {
                LOG.error("InterfaceURI not found. Check parameter: " + parameter_disamb);
            }
        }
    }

    @Override
    public String getResults() {
        return response;
    }

    @Override
    public Map<String, String> getDefaultParameter() {
        Map<String, String> map = new HashMap<>();
        map.put(parameter_input, FoxConst.NER_EN_EXAMPLE_1);
        map.put(parameter_task, "NER");
        map.put(parameter_output, Lang.RDFXML.getName());
        map.put(parameter_nif, "false");
        map.put(parameter_foxlight, "OFF");
        return map;
    }

    @Override
    public String getLog() {
        return foxWebLog.getConsoleOutput();
    }

    private void infoLog(String m) {
        if (foxWebLog != null)
            foxWebLog.setMessage(m);
        LOG.info(m);
    }

    @Override
    public String getLang() {
        return lang;
    }
}
