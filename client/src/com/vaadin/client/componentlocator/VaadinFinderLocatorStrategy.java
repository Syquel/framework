/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.client.componentlocator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.Widget;
import com.vaadin.client.ApplicationConnection;
import com.vaadin.client.ComponentConnector;
import com.vaadin.client.HasComponentsConnector;
import com.vaadin.client.Util;
import com.vaadin.client.metadata.Property;
import com.vaadin.client.metadata.TypeDataStore;
import com.vaadin.client.ui.AbstractConnector;
import com.vaadin.client.ui.SubPartAware;
import com.vaadin.client.ui.VNotification;

/**
 * The VaadinFinder locator strategy implements an XPath-like syntax for
 * locating elements in Vaadin applications. This is used in the new
 * VaadinFinder API in TestBench 4.
 * 
 * Examples of the supported syntax:
 * <ul>
 * <li>Find the third text field in the DOM: {@code //VTextField[2]}</li>
 * <li>Find the second button inside the first vertical layout:
 * {@code //VVerticalLayout/VButton[1]}</li>
 * <li>Find the first column on the third row of the "Accounts" table:
 * {@code //VScrollTable[caption="Accounts"]#row[2]/col[0]}</li>
 * </ul>
 * 
 * @since 7.2
 * @author Vaadin Ltd
 */
public class VaadinFinderLocatorStrategy implements LocatorStrategy {

    public static final String SUBPART_SEPARATOR = "#";

    private final ApplicationConnection client;

    /**
     * Internal descriptor for connector/element/widget name combinations
     */
    private static final class ConnectorPath {
        private String name;
        private ComponentConnector connector;
    }

    public VaadinFinderLocatorStrategy(ApplicationConnection clientConnection) {
        client = clientConnection;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPathForElement(
            com.google.gwt.user.client.Element targetElement) {
        if (targetElement == null) {
            return "";
        }

        List<ConnectorPath> hierarchy = getConnectorHierarchyForElement(targetElement);
        List<String> path = new ArrayList<String>();

        // Assemble longname path components back-to-forth with useful
        // predicates - first try ID, then caption.
        for (int i = 0; i < hierarchy.size(); ++i) {
            ConnectorPath cp = hierarchy.get(i);
            String pathFragment = cp.name;
            String identifier = getPropertyValue(cp.connector, "id");

            if (identifier != null) {
                pathFragment += "[id=\"" + identifier + "\"]";
            } else {
                identifier = getPropertyValue(cp.connector, "caption");
                if (identifier != null) {
                    pathFragment += "[caption=\"" + identifier + "\"]";
                }
            }
            path.add(pathFragment);
        }

        if (path.size() == 0) {
            // If we didn't find a single element, return null..
            return null;
        }

        return getBestSelector(generateQueries(path), targetElement);
    }

    /**
     * Search different queries for the best one. Use the fact that the lowest
     * possible index is with the last selector. Last selector is the full
     * search path containing the complete Component hierarchy.
     * 
     * @param selectors
     *            List of selectors
     * @param target
     *            Target element
     * @return Best selector string formatted with a post filter
     */
    private String getBestSelector(List<String> selectors,
            com.google.gwt.user.client.Element target) {
        // The last selector gives us smallest list index for target element.
        String bestSelector = selectors.get(selectors.size() - 1);
        int min = getElementsByPath(bestSelector).indexOf(target);
        if (selectors.size() > 1
                && min == getElementsByPath(selectors.get(0)).indexOf(target)) {
            // The first selector has same index as last. It's much shorter.
            bestSelector = selectors.get(0);
        } else if (selectors.size() > 2) {
            // See if we get minimum from second last. If not then we already
            // have the best one.. Second last one contains almost full
            // component hierarchy.
            if (getElementsByPath(selectors.get(selectors.size() - 2)).indexOf(
                    target) == min) {
                for (int i = 1; i < selectors.size() - 2; ++i) {
                    // Loop through the remaining selectors and look for one
                    // with the same index
                    if (getElementsByPath(selectors.get(i)).indexOf(target) == min) {
                        bestSelector = selectors.get(i);
                        break;
                    }
                }

            }
        }
        return "(" + bestSelector + ")[" + min + "]";

    }

    /**
     * Function to generate all possible search paths for given component list.
     * Function strips out all the com.vaadin.ui. prefixes from elements as this
     * functionality makes generating a query later on easier.
     * 
     * @param components
     *            List of components
     * @return List of Vaadin selectors
     */
    private List<String> generateQueries(List<String> components) {
        // Prepare to loop through all the elements.
        List<String> paths = new ArrayList<String>();
        int compIdx = 0;
        String basePath = components.get(compIdx).replace("com.vaadin.ui.", "");
        // Add a basic search for the first element (eg. //Button)
        paths.add((components.size() == 1 ? "/" : "//") + basePath);
        while (++compIdx < components.size()) {
            // Loop through the remaining components
            for (int i = components.size() - 1; i >= compIdx; --i) {
                boolean recursive = false;
                if (i > compIdx) {
                    recursive = true;
                }
                paths.add((i == components.size() - 1 ? "/" : "//")
                        + components.get(i).replace("com.vaadin.ui.", "")
                        + (recursive ? "//" : "/") + basePath);
            }
            // Add the element at index compIdx to the basePath so it is
            // included in all the following searches.
            basePath = components.get(compIdx).replace("com.vaadin.ui.", "")
                    + "/" + basePath;
        }

        return paths;
    }

    /**
     * Helper method to get the string-form value of a named property of a
     * component connector
     * 
     * @since 7.2
     * @param c
     *            any ComponentConnector instance
     * @param propertyName
     *            property name to test for
     * @return a string, if the property is found, or null, if the property does
     *         not exist on the object (or some other error is encountered).
     */
    private String getPropertyValue(ComponentConnector c, String propertyName) {
        Property prop = AbstractConnector.getStateType(c).getProperty(
                propertyName);
        try {
            return prop.getValue(c.getState()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Generate a list representing the top-to-bottom connector hierarchy for
     * any given element. ConnectorPath element provides long- and short names,
     * as well as connector and widget root element references.
     * 
     * @since 7.2
     * @param elem
     *            any Element that is part of a widget hierarchy
     * @return a list of ConnectorPath objects, in descending order towards the
     *         common root container.
     */
    private List<ConnectorPath> getConnectorHierarchyForElement(
            com.google.gwt.user.client.Element elem) {
        com.google.gwt.user.client.Element e = elem;
        ComponentConnector c = Util.findPaintable(client, e);
        List<ConnectorPath> connectorHierarchy = new ArrayList<ConnectorPath>();

        while (c != null) {

            for (String id : getIDsForConnector(c)) {
                ConnectorPath cp = new ConnectorPath();
                cp.name = getFullClassName(id);
                cp.connector = c;

                // We want to make an exception for the UI object, since it's
                // our default search context (and can't be found inside itself)
                if (!cp.name.equals("com.vaadin.ui.UI")) {
                    connectorHierarchy.add(cp);
                }
            }

            e = (com.google.gwt.user.client.Element) e.getParentElement();
            if (e != null) {
                c = Util.findPaintable(client, e);
                e = c != null ? c.getWidget().getElement() : null;
            }

        }

        return connectorHierarchy;
    }

    private boolean isNotificationExpression(String path) {
        String[] starts = { "//", "/" };

        String[] frags = { "com.vaadin.ui.Notification.class",
                "com.vaadin.ui.Notification", "VNotification.class",
                "VNotification", "Notification.class", "Notification" };

        String[] ends = { "/", "[" };

        for (String s : starts) {
            for (String f : frags) {
                if (path.equals(s + f)) {
                    return true;
                }

                for (String e : ends) {
                    if (path.startsWith(s + f + e)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<com.google.gwt.user.client.Element> getElementsByPath(
            String path) {
        List<SelectorPredicate> postFilters = SelectorPredicate
                .extractPostFilterPredicates(path);
        if (postFilters.size() > 0) {
            path = path.substring(1, path.lastIndexOf(')'));
        }

        List<com.google.gwt.user.client.Element> elements = new ArrayList<com.google.gwt.user.client.Element>();
        if (isNotificationExpression(path)) {

            for (VNotification n : findNotificationsByPath(path)) {
                elements.add(n.getElement());
            }

        } else {

            elements.addAll(eliminateDuplicates(getElementsByPathStartingAtConnector(
                    path, client.getUIConnector())));
        }

        for (SelectorPredicate p : postFilters) {
            // Post filtering supports only indexes and follows instruction
            // blindly. Index that is outside of our list results into an empty
            // list and multiple indexes are likely to ruin a search completely
            if (p.getIndex() >= 0) {
                if (p.getIndex() >= elements.size()) {
                    elements.clear();
                } else {
                    com.google.gwt.user.client.Element e = elements.get(p
                            .getIndex());
                    elements.clear();
                    elements.add(e);
                }
            }
        }

        return elements;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public com.google.gwt.user.client.Element getElementByPath(String path) {
        List<com.google.gwt.user.client.Element> elements = getElementsByPath(path);
        if (elements.isEmpty()) {
            return null;
        }
        return elements.get(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public com.google.gwt.user.client.Element getElementByPathStartingAt(
            String path, com.google.gwt.user.client.Element root) {
        List<com.google.gwt.user.client.Element> elements = getElementsByPathStartingAt(
                path, root);
        if (elements.isEmpty()) {
            return null;
        }
        return elements.get(0);

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<com.google.gwt.user.client.Element> getElementsByPathStartingAt(
            String path, com.google.gwt.user.client.Element root) {
        List<SelectorPredicate> postFilters = SelectorPredicate
                .extractPostFilterPredicates(path);
        if (postFilters.size() > 0) {
            path = path.substring(1, path.lastIndexOf(')'));
        }

        List<com.google.gwt.user.client.Element> elements = getElementsByPathStartingAtConnector(
                path, Util.findPaintable(client, root));

        for (SelectorPredicate p : postFilters) {
            // Post filtering supports only indexes and follows instruction
            // blindly. Index that is outside of our list results into an empty
            // list and multiple indexes are likely to ruin a search completely
            if (p.getIndex() >= 0) {
                if (p.getIndex() >= elements.size()) {
                    elements.clear();
                } else {
                    com.google.gwt.user.client.Element e = elements.get(p
                            .getIndex());
                    elements.clear();
                    elements.add(e);
                }
            }
        }

        return elements;
    }

    /**
     * Special case for finding notifications as they have no connectors and are
     * directly attached to {@link RootPanel}.
     * 
     * @param path
     *            The path of the notification, should be
     *            {@code "//VNotification"} optionally followed by an index in
     *            brackets.
     * @return the notification element or null if not found.
     */
    private List<VNotification> findNotificationsByPath(String path) {

        List<VNotification> notifications = new ArrayList<VNotification>();
        for (Widget w : RootPanel.get()) {
            if (w instanceof VNotification) {
                notifications.add((VNotification) w);
            }
        }

        List<SelectorPredicate> predicates = SelectorPredicate
                .extractPredicates(path);
        for (SelectorPredicate p : predicates) {

            if (p.getIndex() > -1) {
                VNotification n = notifications.get(p.getIndex());
                notifications.clear();
                if (n != null) {
                    notifications.add(n);
                }
            }

        }

        return eliminateDuplicates(notifications);
    }

    /**
     * Finds a list of elements by the specified path, starting traversal of the
     * connector hierarchy from the specified root.
     * 
     * @param path
     *            the locator path
     * @param root
     *            the root connector
     * @return the list of elements identified by path or empty list if not
     *         found.
     */
    private List<com.google.gwt.user.client.Element> getElementsByPathStartingAtConnector(
            String path, ComponentConnector root) {
        String[] pathComponents = path.split(SUBPART_SEPARATOR);
        List<ComponentConnector> connectors;
        if (pathComponents[0].length() > 0) {
            connectors = findConnectorsByPath(pathComponents[0],
                    Arrays.asList(root));
        } else {
            connectors = Arrays.asList(root);
        }

        List<com.google.gwt.user.client.Element> output = new ArrayList<com.google.gwt.user.client.Element>();
        if (null != connectors && !connectors.isEmpty()) {
            if (pathComponents.length > 1) {
                // We have subparts
                for (ComponentConnector connector : connectors) {
                    if (connector.getWidget() instanceof SubPartAware) {
                        output.add(((SubPartAware) connector.getWidget())
                                .getSubPartElement(pathComponents[1]));
                    }
                }
            } else {
                for (ComponentConnector connector : connectors) {
                    output.add(connector.getWidget().getElement());
                }
            }
        }
        return eliminateDuplicates(output);
    }

    /**
     * Recursively finds connectors for the elements identified by the provided
     * path by traversing the connector hierarchy starting from {@code parents}
     * connectors.
     * 
     * @param path
     *            The path identifying elements.
     * @param parents
     *            The list of connectors to start traversing from.
     * @return The list of connectors identified by {@code path} or empty list
     *         if no such connectors could be found.
     */
    private List<ComponentConnector> findConnectorsByPath(String path,
            List<ComponentConnector> parents) {
        boolean findRecursively = path.startsWith("//");
        // Strip away the one or two slashes from the beginning of the path
        path = path.substring(findRecursively ? 2 : 1);

        String[] fragments = splitFirstFragmentFromTheRest(path);

        List<ComponentConnector> connectors = new ArrayList<ComponentConnector>();
        for (ComponentConnector parent : parents) {
            connectors.addAll(filterMatches(
                    collectPotentialMatches(parent, fragments[0],
                            findRecursively), SelectorPredicate
                            .extractPredicates(fragments[0])));
        }

        if (!connectors.isEmpty() && fragments.length > 1) {
            return (findConnectorsByPath(fragments[1], connectors));
        }
        return eliminateDuplicates(connectors);
    }

    /**
     * Go through a list of potentially matching components, modifying that list
     * until all elements that remain in that list match the complete list of
     * predicates.
     * 
     * @param potentialMatches
     *            a list of component connectors. Will be changed.
     * @param predicates
     *            an immutable list of predicates
     * @return filtered list of component connectors.
     */
    private List<ComponentConnector> filterMatches(
            List<ComponentConnector> potentialMatches,
            List<SelectorPredicate> predicates) {

        for (SelectorPredicate p : predicates) {

            if (p.getIndex() > -1) {
                try {
                    ComponentConnector v = potentialMatches.get(p.getIndex());
                    potentialMatches.clear();
                    potentialMatches.add(v);
                } catch (IndexOutOfBoundsException e) {
                    potentialMatches.clear();
                }

                continue;
            }

            for (int i = 0, l = potentialMatches.size(); i < l; ++i) {

                String propData = getPropertyValue(potentialMatches.get(i),
                        p.getName());

                if ((p.isWildcard() && propData == null)
                        || (!p.isWildcard() && !p.getValue().equals(propData))) {
                    potentialMatches.remove(i);
                    --l;
                    --i;
                }
            }

        }

        return eliminateDuplicates(potentialMatches);
    }

    /**
     * Collects all connectors that match the widget class name of the path
     * fragment. If the {@code collectRecursively} parameter is true, a
     * depth-first search of the connector hierarchy is performed.
     * 
     * Searching depth-first ensure that we can return the matches in correct
     * order for selecting based on index predicates.
     * 
     * @param parent
     *            The {@link ComponentConnector} to start the search from.
     * @param pathFragment
     *            The path fragment identifying which type of widget to search
     *            for.
     * @param collectRecursively
     *            If true, all matches from all levels below {@code parent} will
     *            be collected. If false only direct children will be collected.
     * @return A list of {@link ComponentConnector}s matching the widget type
     *         specified in the {@code pathFragment}.
     */
    private List<ComponentConnector> collectPotentialMatches(
            ComponentConnector parent, String pathFragment,
            boolean collectRecursively) {
        ArrayList<ComponentConnector> potentialMatches = new ArrayList<ComponentConnector>();
        if (parent instanceof HasComponentsConnector) {
            List<ComponentConnector> children = ((HasComponentsConnector) parent)
                    .getChildComponents();
            for (ComponentConnector child : children) {
                String widgetName = getWidgetName(pathFragment);
                if (connectorMatchesPathFragment(child, widgetName)) {
                    potentialMatches.add(child);
                }
                if (collectRecursively) {
                    potentialMatches.addAll(collectPotentialMatches(child,
                            pathFragment, collectRecursively));
                }
            }
        }
        return eliminateDuplicates(potentialMatches);
    }

    private List<String> getIDsForConnector(ComponentConnector connector) {
        Class<?> connectorClass = connector.getClass();
        List<String> ids = new ArrayList<String>();

        TypeDataStore.get().findIdentifiersFor(connectorClass).addAllTo(ids);

        return ids;
    }

    /**
     * Determines whether a connector matches a path fragment. This is done by
     * comparing the path fragment to the name of the widget type of the
     * connector.
     * 
     * @param connector
     *            The connector to compare.
     * @param widgetName
     *            The name of the widget class.
     * @return true if the widget type of the connector equals the widget type
     *         identified by the path fragment.
     */
    private boolean connectorMatchesPathFragment(ComponentConnector connector,
            String widgetName) {

        List<String> ids = getIDsForConnector(connector);

        Integer[] widgetTags = client.getConfiguration()
                .getTagsForServerSideClassName(getFullClassName(widgetName));
        if (widgetTags.length == 0) {
            widgetTags = client.getConfiguration()
                    .getTagsForServerSideClassName(
                            getFullClassName("com.vaadin.ui." + widgetName));
        }

        for (int i = 0, l = ids.size(); i < l; ++i) {

            // Fuzz the connector name, so that the client can provide (for
            // example: /Button, /Button.class, /com.vaadin.ui.Button,
            // /com.vaadin.ui.Button.class, etc)

            String name = ids.get(i);
            final String simpleName = getSimpleClassName(name);
            final String fullName = getFullClassName(name);

            if (widgetTags.length > 0) {
                Integer[] foundTags = client.getConfiguration()
                        .getTagsForServerSideClassName(fullName);
                for (int tag : foundTags) {
                    if (tagsMatch(widgetTags, tag)) {
                        return true;
                    }
                }
            }

            // Fallback if something failed before.
            if (widgetName.equals(fullName + ".class")
                    || widgetName.equals(fullName)
                    || widgetName.equals(simpleName + ".class")
                    || widgetName.equals(simpleName) || widgetName.equals(name)) {
                return true;
            }
        }

        // If the server-side class name didn't match, fall back to testing for
        // the explicit widget name
        String widget = Util.getSimpleName(connector.getWidget());
        return widgetName.equals(widget)
                || widgetName.equals(widget + ".class");

    }

    /**
     * Extracts the name of the widget class from a path fragment
     * 
     * @param pathFragment
     *            the path fragment
     * @return the name of the widget class.
     */
    private String getWidgetName(String pathFragment) {
        String widgetName = pathFragment;
        int ixBracket = pathFragment.indexOf('[');
        if (ixBracket >= 0) {
            widgetName = pathFragment.substring(0, ixBracket);
        }
        return widgetName;
    }

    /**
     * Splits off the first path fragment from a path and returns an array of
     * two elements, where the first element is the first path fragment and the
     * second element is the rest of the path (all remaining path fragments
     * untouched).
     * 
     * @param path
     *            The path to split.
     * @return An array of two elements: The first path fragment and the rest of
     *         the path.
     */
    private String[] splitFirstFragmentFromTheRest(String path) {
        int ixOfSlash = LocatorUtil.indexOfIgnoringQuoted(path, '/');
        if (ixOfSlash > 0) {
            return new String[] { path.substring(0, ixOfSlash),
                    path.substring(ixOfSlash) };
        }
        return new String[] { path };
    }

    private String getSimpleClassName(String s) {
        String[] parts = s.split("\\.");
        if (s.endsWith(".class")) {
            return parts[parts.length - 2];
        }
        return parts.length > 0 ? parts[parts.length - 1] : s;
    }

    private String getFullClassName(String s) {
        if (s.endsWith(".class")) {
            return s.substring(0, s.lastIndexOf(".class"));
        }
        return s;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.vaadin.client.componentlocator.LocatorStrategy#validatePath(java.
     * lang.String)
     */
    @Override
    public boolean validatePath(String path) {
        // This syntax is so difficult to regexp properly, that we'll just try
        // to find something with it regardless of the correctness of the
        // syntax...
        return true;
    }

    /**
     * Go through a list, removing all duplicate elements from it. This method
     * is used to avoid accumulation of duplicate entries in result lists
     * resulting from low-context recursion.
     * 
     * Preserves first entry in list, removes others. Preserves list order.
     * 
     * @return list passed as parameter, after modification
     */
    private final <T> List<T> eliminateDuplicates(List<T> list) {

        int l = list.size();
        for (int j = 0; j < l; ++j) {
            T ref = list.get(j);

            for (int i = j + 1; i < l; ++i) {
                if (list.get(i) == ref) {
                    list.remove(i);
                    --i;
                    --l;
                }
            }
        }

        return list;
    }

    private boolean tagsMatch(Integer[] targets, Integer tag) {
        for (int i = 0; i < targets.length; ++i) {
            if (targets[i].equals(tag)) {
                return true;
            }
        }

        try {
            return tagsMatch(targets,
                    client.getConfiguration().getParentTag(tag));
        } catch (Exception e) {
            return false;
        }
    }
}
