/*
 *    Copyright 2019 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package io.dockstore.webservice.resources;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.Alias;
import io.dockstore.webservice.core.Aliasable;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.helpers.StateManagerMode;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;

public interface AliasableResourceInterface<T extends Aliasable> {
    // reserve some prefixes for our own use
    String[] INVALID_PREFIXES = {"dockstore", "doi", "drs", "trs", "dos", "wes"};
    String ZENDO_DOI_REGEX = "^\\d\\d\\.\\d\\d\\d\\d[/-]zenodo\\.\\d+$";
    Pattern ZENODO_DOI_PATTERN = Pattern.compile(ZENDO_DOI_REGEX);

    /**
     * TODO: evaluate whether this makes sense after I converted elastic manager to a singleton
     * @return
     */
    Optional<PublicStateManager> getPublicStateManager();

    /**
     * Get a resource with id and only return it if user has rights to see/change it
     *
     * @param user
     * @param id
     * @return
     */
    T getAndCheckResource(User user, Long id);

    /**
     * Get aliases, only works on public entries for now
     * @param alias
     * @return
     */
    T getAndCheckResourceByAlias(String alias);

    /**
     * Check that aliases do not contain invalid prefixes
     * Code has changed to only allow the owner of the entry to add an alias.
     * @param aliases a Set of alias strings
     * @param user user authenticated to issue a DOI for the workflow
     * @param blockAliasesWithZenodoFormat block creation of an alias with a particular format
     */
    static void checkAliases(Set<String>  aliases, User user, boolean blockAliasesWithZenodoFormat) {
        // Admins and curators do not have restrictions on alias format
        if (user.isCurator() || user.getIsAdmin()) {
            return;
        }
        checkAliasFormat(aliases, blockAliasesWithZenodoFormat);
    }


    /**
     * Check that aliases do not contain invalid prefixes
     * if the user adding them is not an admin or curator
     * @param aliases a Set of alias strings
     * @param blockAliasesWithZenodoFormat block creation of an alias with a particular format
     */
    static void checkAliasFormat(Set<String>  aliases, boolean blockAliasesWithZenodoFormat) {
        // Gather up any aliases that contain invalid prefixes
        List<String> invalidAliases = aliases.stream().filter(alias -> StringUtils.startsWithAny(alias, INVALID_PREFIXES))
                .collect(Collectors.toList());

        // If there are any aliases with invalid prefixes then report it to the user
        if (invalidAliases.size() > 0) {
            String invalidAliasesString = String.join(", ", invalidAliases);
            String invalidPrefixesString = String.join(", ", INVALID_PREFIXES);
            throw new CustomWebApplicationException("These aliases: " + invalidAliasesString + " start with a reserved string."
                    + " They cannot be used. Please create aliases without these prefixes: " + invalidPrefixesString,
                    HttpStatus.SC_BAD_REQUEST);
        }

        if (blockAliasesWithZenodoFormat) {
            List<String> aliasesWithForbiddenFormat = aliases.stream().filter(alias -> ZENODO_DOI_PATTERN.matcher(alias).matches())
                    .collect(Collectors.toList());
            // If there are any aliases with invalid formats then report it to the user
            if (aliasesWithForbiddenFormat.size() > 0) {
                String invalidAliasesString = String.join(", ", aliasesWithForbiddenFormat);
                throw new CustomWebApplicationException("These aliases : " + invalidAliasesString + " have a format that is forbidden."
                        + " They cannot be used. Please create aliases without this format: " + ZENDO_DOI_REGEX,
                        HttpStatus.SC_BAD_REQUEST);
            }
        }
    }

    /**
     * Add aliases to an Entry (e.g. Workflow or Tool)
     * and check that they are valid before adding them
     * @param user user authenticated to issue a DOI for the workflow
     * @param id the id of the Entry
     * @param aliases a comma separated string of aliases
     * @return the alias as a string
     */
    default T addAliases(User user, Long id, String aliases) {
        return addAliasesAndCheck(user, id, aliases, true);
    }

    /**
     * Add aliases to an Entry (e.g. Workflow or Tool)
     * and check that they are valid before adding them:
     * Only works for owners of the entry
     * If blockFormat false, then no limit on format
     * @param user user authenticated to issue a DOI for the workflow
     * @param id the id of the Entry
     * @param aliases a comma separated string of aliases
     * @param blockFormat if true don't allow specific formats
     * @return the resource
     */
    default T addAliasesAndCheck(User user, Long id, String aliases, boolean blockFormat) {
        T c = getAndCheckResource(user, id);
        Set<String> oldAliases = c.getAliases().keySet();
        Set<String> newAliases = Sets.newHashSet(Arrays.stream(aliases.split(",")).map(String::trim).toArray(String[]::new));

        checkAliases(newAliases, user, blockFormat);

        Set<String> duplicateAliasesToAdd = Sets.intersection(newAliases, oldAliases);
        if (!duplicateAliasesToAdd.isEmpty()) {
            String dupAliasesString = String.join(", ", duplicateAliasesToAdd);
            throw new CustomWebApplicationException("Aliases " + dupAliasesString + " already exist; please use unique aliases",
                    HttpStatus.SC_BAD_REQUEST);
        }

        newAliases.forEach(alias -> c.getAliases().put(alias, new Alias()));

        if (c instanceof Entry) {
            getPublicStateManager().ifPresent(consumer -> consumer.handleIndexUpdate((Entry)c, StateManagerMode.UPDATE));
        }
        return c;
    }
}
