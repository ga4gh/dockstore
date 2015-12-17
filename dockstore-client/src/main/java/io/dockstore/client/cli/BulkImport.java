/*
 * Copyright (C) 2015 Dockstore
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.dockstore.client.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.RepositoryContents;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.ContentsService;
import org.eclipse.egit.github.core.service.RepositoryService;

import com.esotericsoftware.yamlbeans.YamlReader;
import com.google.common.base.Joiner;
import com.google.gson.Gson;

import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.UsersApi;
import io.swagger.client.model.Container;
import io.swagger.client.model.Tag;
import io.swagger.client.model.Token;
import io.swagger.client.model.User;

/**
 *
 * @author xliu
 */
public class BulkImport {
    private final ContainersApi containersApi;
    private final UsersApi usersApi;
    private final User user;

    public BulkImport(ContainersApi containersApi, UsersApi usersApi, User user) {
        this.containersApi = containersApi;
        this.usersApi = usersApi;
        this.user = user;
    }

    private void out(String format, Object... args) {
        System.out.println(String.format(format, args));
    }

    private void out(String arg) {
        System.out.println(arg);
    }

    private void err(String format, Object... args) {
        System.err.println(String.format(format, args));
    }

    private class Kill extends RuntimeException {
    }

    private void kill(String format, Object... args) {
        err(format, args);
        throw new Kill();
    }

    private String getDockerSource(RepositoryContents file, ContentsService cService, Repository repo, String reference) {
        String dockerPull = null;

        try {
            List<RepositoryContents> contents;
            contents = cService.getContents(repo, file.getPath(), reference);
            if (!(contents == null || contents.isEmpty())) {
                String encoded = contents.get(0).getContent().replace("\n", "");
                byte[] decode = Base64.getDecoder().decode(encoded);
                String content = new String(decode, StandardCharsets.UTF_8);

                if (content != null && !content.isEmpty()) {
                    try {
                        YamlReader reader = new YamlReader(content);
                        Object object = reader.read();

                        // need to parse this to get the imports
                        // use the dockerPull from the DockerRequirement class to obtain the Docker Hub path and tag version
                        Map<String, List<Map<String, String>>> map = new HashMap<>();

                        map = (Map<String, List<Map<String, String>>>) object;

                        List<Map<String, String>> listOfImports = map.get("requirements");

                        if (listOfImports != null) {
                            for (Map<String, String> anImport : listOfImports) {
                                String importCwl = anImport.get("import");

                                if (importCwl == null) {
                                    importCwl = anImport.get("@import");
                                }
                                if (importCwl != null) {
                                    out("Import: " + importCwl);
                                }
                            }
                        } else {
                            out("does not include requirements");
                        }

                    } catch (IOException ex) {
                        err("Could not parse cwl for ", file.getName());
                    }

                }
            }
        } catch (Exception e) {
            kill(e.toString());
        }

        return dockerPull;
    }

    private void removeNonCwl(List<RepositoryContents> contents, String stringToAppend) {
        for (Iterator<RepositoryContents> iterator = contents.iterator(); iterator.hasNext();) {
            RepositoryContents content = iterator.next();
            String name = content.getName();
            Pattern p = Pattern.compile("^([\\w-]+)\\.cwl$");
            Matcher m = p.matcher(name);
            if (!m.find()) {
                iterator.remove();
                continue;
            }
            name = m.group(1);
            content.setName(name.concat(stringToAppend));
        }
    }

    public void run() {
        GitHubClient githubClient = new GitHubClient();
        try {
            List<Token> githubToken = usersApi.getGithubUserTokens(user.getId());
            githubClient.setOAuth2Token(githubToken.get(0).getContent());
        } catch (final ApiException ex) {
            err(ex.getResponseBody());
            kill("Unable to get Github token");
        }

        RepositoryService service = new RepositoryService(githubClient);
        ContentsService cService = new ContentsService(githubClient);

        List<RepositoryContents> contents = new ArrayList<>();
        List<RepositoryContents> bulkContents = new ArrayList<>();

        final String reference = "draft2";
        Repository repo = null;

        try {
            repo = service.getRepository("common-workflow-language", "workflows");
            try {
                contents.addAll(cService.getContents(repo, "/tools", reference));
            } catch (Exception e) {
                kill(e.toString());
            }
            try {
                bulkContents.addAll(cService.getContents(repo, "/tools/bulk", reference));
            } catch (Exception e) {
                kill(e.toString());
            }

        } catch (IOException ex) {
            err("Unable to find repository");
            kill(ex.toString());
        }

        removeNonCwl(contents, "");
        removeNonCwl(bulkContents, "-bulk");
        contents.addAll(bulkContents);

        Gson gson = new Gson();
        String json = gson.toJson(contents);

        // out(json);

        for (RepositoryContents content : contents) {
            if (!content.getType().equals("file")) {
                continue;
            }

            String dockerPull = getDockerSource(content, cService, repo, reference);

            String name = content.getName();
            out(name);
            String path = content.getPath();
            String namespace = "common-workflow-language";
            String registry = "registry.hub.docker.com";

            Container container = new Container();
            container.setMode(Container.ModeEnum.MANUAL_IMAGE_PATH);
            container.setName(name);
            container.setNamespace(namespace);
            container.setRegistry("quay.io".equals(registry) ? Container.RegistryEnum.QUAY_IO : Container.RegistryEnum.DOCKER_HUB);
            // container.setDefaultDockerfilePath(dockerfilePath);
            container.setDefaultCwlPath(path);
            container.setIsPublic(true);
            container.setIsRegistered(true);
            container.setGitUrl("https://github.com/common-workflow-language/workflows");
            // container.setToolname(toolname);
            Tag tag = new Tag();
            tag.setName(reference);
            tag.setReference(reference);
            // tag.setDockerfilePath();
            tag.setCwlPath(path);
            container.getTags().add(tag);
            String fullName = Joiner.on("/").skipNulls().join(registry, namespace, name);
            try {
                container = containersApi.registerManual(container);
                if (container == null) {
                    err("Unable to publish " + fullName);
                }
            } catch (final ApiException ex) {
                err("Unable to publish " + fullName);
            }

        }
        out("updating...");
        // refresh(null);

    }
}
