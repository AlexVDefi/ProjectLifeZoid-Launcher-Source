package zombie.characters.action;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

public final class ActionGroup {
    private String name;
    private String initialStateName;
    private final List<ActionState> states = new ArrayList<>();
    private final Map<String, ActionState> stateLookup = new HashMap<>();
    private final Map<Integer, String> stateNameLookup = new HashMap<>();
    private static final Map<String, ActionGroup> s_actionGroupMap = new HashMap<>();

    private void load() {
        String name = this.name;
        DebugType.ActionSystem.debugln("Loading ActionGroup: %s", name);
        File actionGroupFile = ZomboidFileSystem.instance.getMediaFile("actiongroups/" + name + "/actionGroup.xml");
        if (actionGroupFile.exists() && actionGroupFile.canRead()) {
            this.loadGroupData(actionGroupFile);
        }

        String[] stateDirs = ZomboidFileSystem.instance.resolveAllDirectories("media/actiongroups/" + name, dir -> true, false);

        for (String stateDir : stateDirs) {
            ActionState state = this.getOrCreate(new File(stateDir).getName());
            state.load(stateDir);
        }
    }

    private void loadGroupData(File groupDataFile) {
        Document doc;
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            doc = dBuilder.parse(groupDataFile);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            DebugType.ActionSystem.printException(e, "Error loading: " + groupDataFile.getPath(), LogSeverity.Error);
            return;
        }

        doc.getDocumentElement().normalize();
        Element elem = doc.getDocumentElement();
        if (!elem.getNodeName().equals("actiongroup")) {
            DebugType.ActionSystem
                .error("Error loading: " + groupDataFile.getPath() + ", expected root element '<actiongroup>', received '<" + elem.getNodeName() + ">'");
        } else {
            for (Node child = elem.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element childElem && childElem.getNodeName().equals("initial")) {
                    this.initialStateName = childElem.getTextContent().trim();
                }
            }
        }
    }

    public ActionState addState(ActionState state) {
        if (this.states.contains(state)) {
            DebugType.ActionSystem.trace("State already added.");
            return state;
        } else {
            state.setParentActionGroup(this);
            this.states.add(state);
            this.stateLookup.put(state.getName().toLowerCase(), state);
            this.stateNameLookup.put(state.getName().hashCode(), state.getName());
            return state;
        }
    }

    public ActionState findState(String stateName) {
        return this.stateLookup.get(stateName.toLowerCase());
    }

    public ActionState getOrCreate(String stateName) {
        stateName = stateName.toLowerCase();
        ActionState state = this.findState(stateName);
        if (state == null) {
            state = this.addState(new ActionState(stateName));
        }

        return state;
    }

    public ActionState getInitialState() {
        ActionState state = null;
        if (this.initialStateName != null) {
            state = this.findState(this.initialStateName);
        }

        if (state == null && !this.states.isEmpty()) {
            state = this.states.get(0);
        }

        return state;
    }

    public ActionState getDefaultState() {
        return this.getInitialState();
    }

    public String getName() {
        return this.name;
    }

    /**
     * PLZ: guards the two static entry points that mutate s_actionGroupMap.
     *
     * getActionGroup is get-then-put on a plain HashMap. The SPVThread reaches it through
     * IsoAnimal.init -> initType while loading animal-carrying vehicles, while the main thread
     * calls it per zombie per tick from IsoZombie.updateInternal and at every character creation.
     * That is the same unsynchronised-map race that corrupts the animation asset table.
     *
     * A private lock rather than marking the methods synchronized: the fidelity gate compares
     * javap output including modifiers, so adding "synchronized" to the signature reads as a
     * changed member and fails the build. Lock order is consistent with the AnimationSet lock -
     * a group load may reach an anim set, never the reverse - so the two cannot deadlock.
     *
     * Cost on the server JVM is ~3 ns uncontended, roughly 25 us/tick at 5,000 loaded zombies,
     * which is noise against a 70 ms tick.
     */
    private static final Object PLZ_GROUP_MAP_LOCK = new Object();

    public static ActionGroup getActionGroup(String groupName) {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ACTION_GROUP_SYNC)) {
            synchronized (PLZ_GROUP_MAP_LOCK) {
                zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.ACTION_GROUP_SYNC);
                return plzGetActionGroup(groupName);
            }
        }

        return plzGetActionGroup(groupName);
    }

    private static ActionGroup plzGetActionGroup(String groupName) {
        groupName = groupName.toLowerCase();
        ActionGroup grp = s_actionGroupMap.get(groupName);
        if (grp == null && !s_actionGroupMap.containsKey(groupName)) {
            grp = new ActionGroup();
            grp.name = groupName;
            s_actionGroupMap.put(groupName, grp);

            try {
                grp.load();
            } catch (Exception e) {
                DebugType.ActionSystem.printException(e, "Error loading action group: " + groupName, LogSeverity.Error);
            }

            return grp;
        } else {
            return grp;
        }
    }

    public static void reloadAll() {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ACTION_GROUP_SYNC)) {
            synchronized (PLZ_GROUP_MAP_LOCK) {
                plzReloadAll();
                return;
            }
        }

        plzReloadAll();
    }

    private static void plzReloadAll() {
        for (Entry<String, ActionGroup> entry : s_actionGroupMap.entrySet()) {
            ActionGroup actionGroup = entry.getValue();

            for (ActionState state : actionGroup.states) {
                state.resetForReload();
            }

            actionGroup.load();
        }
    }
}
