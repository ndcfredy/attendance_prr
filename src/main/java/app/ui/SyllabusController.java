package app.ui;

import app.core.AppContext;
import app.core.Fx;
import app.db.CourseDao;
import app.model.Models.Chapter;
import app.model.Models.Course;
import app.model.Models.CoverageRow;
import app.model.Models.Role;
import app.model.Models.TopicRef;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/** Course plan editor (lecturer) and syllabus coverage dashboard. */
public class SyllabusController {
    @FXML private HBox syllabusRoot;
    @FXML private ChoiceBox<Course> courseChoice;
    @FXML private TreeView<Object> planTree;
    @FXML private VBox editPane;
    @FXML private TextField chapterNumberField;
    @FXML private TextField chapterTitleField;
    @FXML private TextField chapterHoursField;
    @FXML private Button btnAddChapter;
    @FXML private TextField topicTitleField;
    @FXML private Button btnAddTopicToPlan;
    @FXML private Button btnDeleteNode;
    @FXML private Label coverageLabel;
    @FXML private ProgressBar coverageBar;
    @FXML private Label hoursLabel;
    @FXML private Label behindLabel;
    @FXML private TableView<CoverageRow> coverageTable;
    @FXML private TableColumn<CoverageRow, String> colChapter;
    @FXML private TableColumn<CoverageRow, String> colTopics;
    @FXML private TableColumn<CoverageRow, String> colHours;
    @FXML private TableColumn<CoverageRow, String> colState;

    @FXML
    private void initialize() {
        boolean canEdit = AppContext.can(Role.LECTURER, Role.ADMIN);
        editPane.setDisable(!canEdit);
        colChapter.setCellValueFactory(c -> new SimpleStringProperty("Ch." + c.getValue().chapterNo() + " – " + c.getValue().title()));
        colTopics.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().topicsDone() + " / " + c.getValue().topicsTotal()));
        colHours.setCellValueFactory(c -> new SimpleStringProperty(String.format("%.1f h / %.1f h", c.getValue().deliveredHours(), c.getValue().plannedHours())));
        colState.setCellValueFactory(c -> {
            CoverageRow r = c.getValue();
            return new SimpleStringProperty(r.topicsTotal() > 0 && r.topicsDone() >= r.topicsTotal() ? "DELIVERED" : r.topicsDone() > 0 ? "IN_PROGRESS" : "PLANNED");
        });
        Cells.status(colState);
        planTree.setShowRoot(false);
        planTree.setCellFactory(tv -> new TreeCell<>() {
            @Override protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                if (item instanceof Chapter c) setText("Ch." + c.number() + " – " + c.title() + "  (" + c.plannedHours() + " h)");
                else if (item instanceof TopicRef t) setText("•  " + t.title());
                else setText(String.valueOf(item));
            }
        });
        Integer lec = AppContext.user.role() == Role.LECTURER ? AppContext.user.id() : null;
        courseChoice.valueProperty().addListener((o, a, b) -> reload());
        Fx.async(() -> CourseDao.courses(lec), list -> {
            courseChoice.getItems().setAll(list);
            if (!list.isEmpty()) courseChoice.setValue(list.get(0));
        }, null);
    }

    private record Plan(List<Chapter> chapters, List<TopicRef> topics, CourseDao.Coverage coverage) { }

    private void reload() {
        Course c = courseChoice.getValue();
        if (c == null) return;
        Fx.async(() -> new Plan(CourseDao.chapters(c.id()), CourseDao.topicRefs(c.id()), CourseDao.coverage(c.id())), p -> {
            TreeItem<Object> root = new TreeItem<>("plan");
            for (Chapter ch : p.chapters()) {
                TreeItem<Object> node = new TreeItem<>(ch);
                node.setExpanded(true);
                p.topics().stream().filter(t -> t.chapterId() == ch.id()).forEach(t -> node.getChildren().add(new TreeItem<>(t)));
                root.getChildren().add(node);
            }
            planTree.setRoot(root);
            CourseDao.Coverage cov = p.coverage();
            coverageLabel.setText(String.format("Topics delivered: %d of %d (%.0f %%)", cov.topicsDone(), cov.topicsTotal(), cov.topicPercent()));
            coverageBar.setProgress(cov.topicPercent() / 100.0);
            hoursLabel.setText(String.format("Hours delivered: %.1f h of %.1f h planned (%.0f %%)", cov.deliveredHours(), cov.plannedHours(), cov.hoursPercent()));
            behindLabel.setText(cov.behind() ? "⚠ Behind schedule: fewer topics covered than the hours already taught would suggest." : "");
            coverageTable.getItems().setAll(cov.rows());
        }, null);
    }

    @FXML
    private void onAddChapter() {
        Course c = courseChoice.getValue();
        if (c == null) return;
        try {
            int no = Integer.parseInt(chapterNumberField.getText().trim());
            double h = Double.parseDouble(chapterHoursField.getText().trim().replace(',', '.'));
            String title = chapterTitleField.getText().trim();
            if (title.isEmpty()) throw new NumberFormatException();
            Fx.async(() -> { CourseDao.addChapter(c.id(), no, title, h); return null; }, x -> {
                chapterNumberField.clear(); chapterTitleField.clear(); chapterHoursField.clear(); reload();
            }, e -> Fx.error("Could not add the chapter (number already used?): " + Fx.msg(e)));
        } catch (NumberFormatException e) {
            Fx.error("Give a chapter number, a title and the planned hours (numbers).");
        }
    }

    @FXML
    private void onAddTopic() {
        TreeItem<Object> sel = planTree.getSelectionModel().getSelectedItem();
        String title = topicTitleField.getText().trim();
        if (sel == null || title.isEmpty()) { Fx.error("Select a chapter in the plan and type the topic title."); return; }
        Object v = sel.getValue();
        int chapterId = v instanceof Chapter c ? c.id() : v instanceof TopicRef t ? t.chapterId() : 0;
        if (chapterId == 0) return;
        Fx.async(() -> { CourseDao.addTopic(chapterId, title); return null; }, x -> { topicTitleField.clear(); reload(); }, null);
    }

    @FXML
    private void onDeleteNode() {
        TreeItem<Object> sel = planTree.getSelectionModel().getSelectedItem();
        if (sel == null || !Fx.confirm("Delete the selected chapter/topic from the plan?")) return;
        Object v = sel.getValue();
        Fx.async(() -> {
            if (v instanceof Chapter c) CourseDao.deleteChapter(c.id());
            else if (v instanceof TopicRef t) CourseDao.deleteTopic(t.topicId());
            return null;
        }, x -> reload(), e -> Fx.error("Cannot delete: " + Fx.msg(e)));
    }
}
