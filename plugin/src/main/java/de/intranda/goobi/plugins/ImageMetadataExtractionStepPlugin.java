package de.intranda.goobi.plugins;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.oro.text.perl.Perl5Util;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2
public class ImageMetadataExtractionStepPlugin implements IStepPluginVersion2 {

    @Getter
    private String title = "intranda_step_imageMetadataExtraction";
    @Getter
    private Step step;

    private Process process;
    @Getter
    private String value;
    private String returnPath;

    private static Perl5Util perlUtil = new Perl5Util();

    private Map<String, String> metadataMap = new HashMap<>();

    private String command;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
        process = step.getProzess();
        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        log.info("ImageMetadataExtraction step plugin initialized");
        command = myconfig.getString("command", "/usr/bin/exiftool");

        List<HierarchicalConfiguration> fieldList = myconfig.configurationsAt("field");
        for (HierarchicalConfiguration field : fieldList) {
            String line = field.getString("@line");
            String metadata = field.getString("@metadata");
            metadataMap.put(line, metadata);
        }
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_imageMetadataExtraction.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        try {
            // open metadata
            Prefs prefs = process.getRegelsatz().getPreferences();
            Fileformat ff = new MetsMods(prefs);
            ff.read(process.getMetadataFilePath());

            DigitalDocument dd = ff.getDigitalDocument();
            DocStruct logical = dd.getLogicalDocStruct();

            DocStruct physical = dd.getPhysicalDocStruct();

            // list files in image folder
            List<Path> images = StorageProvider.getInstance().listFiles(process.getImagesTifDirectory(false));

            // order files in image folder
            Collections.sort(images, imageComparator);

            // check/create pagination
            List<DocStruct> pages = physical.getAllChildren();
            int currentPhysicalOrder = 0;
            if (pages == null || pages.size() == 0) {
                for (Path image : images) {
                    DocStruct page = dd.createDocStruct(prefs.getDocStrctTypeByName("page"));
                    page.setImageName(image.toString());
                    MetadataType mdt = prefs.getMetadataTypeByName("physPageNumber");
                    Metadata mdTemp = new Metadata(mdt);
                    mdTemp.setValue(String.valueOf(++currentPhysicalOrder));
                    page.addMetadata(mdTemp);

                    // logical page no
                    mdt = prefs.getMetadataTypeByName("logicalPageNumber");
                    mdTemp = new Metadata(mdt);
                    mdTemp.setValue("uncounted");

                    page.addMetadata(mdTemp);
                    physical.addChild(page);
                    logical.addReferenceTo(page, "logical_physical");

                }
            }

            // read image metadata from first image
            String[] commandLineCall = { command, images.get(0).toString() };

            List<String> exiftoolResponse = new ArrayList<>();
            try {
                java.lang.Process exiftool = Runtime.getRuntime().exec(commandLineCall);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(exiftool.getInputStream()))) {
                    String s;
                    while ((s = reader.readLine()) != null) {
                        exiftoolResponse.add(s);
                    }
                }
            } catch (IOException e1) {
                log.error(e1);
                return PluginReturnValue.ERROR;
            }

            // extract image metadata fields
            for (String line : exiftoolResponse) {
                for (String startValue : metadataMap.keySet()) {
                    if (line.startsWith(startValue)) {
                        String value = line.split(":")[1].trim();
                        try {
                            if (StringUtils.isNotBlank(value)) {
                                MetadataType metadataType = prefs.getMetadataTypeByName(metadataMap.get(startValue));
                                List<? extends Metadata> oldMetadataList = logical.getAllMetadataByType(metadataType);
                                if (oldMetadataList != null && oldMetadataList.size() > 0) {
                                    oldMetadataList.get(0).setValue(value);
                                } else {
                                    Metadata md = new Metadata(prefs.getMetadataTypeByName(metadataMap.get(startValue)));
                                    md.setValue(value);
                                    logical.addMetadata(md);
                                }
                            }
                        } catch (Exception e) {
                            log.error(e);
                            return PluginReturnValue.ERROR;
                        }
                    }
                }
            }
            // save metadata
            ff.write(process.getMetadataFilePath());

        } catch (UGHException | IOException | SwapException e) {
            log.error(e);
        }

        return PluginReturnValue.FINISH;
    }

    private static Comparator<Path> imageComparator = new Comparator<Path>() {

        @Override
        public int compare(Path p1, Path p2) {
            Integer imageId1 = 0;
            Integer imageId2 = 0;
            if (perlUtil.match("/(.*)_(\\d+)\\.jpg/", p1.getFileName().toString())) {
                imageId1 = Integer.valueOf(perlUtil.group(2));
            }
            if (perlUtil.match("/(.*)_(\\d+)\\.jpg/", p2.getFileName().toString())) {
                imageId2 = Integer.valueOf(perlUtil.group(2));
            }
            return imageId1.compareTo(imageId2);
        }
    };

}
