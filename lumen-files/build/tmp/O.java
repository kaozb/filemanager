import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
public final class O {
    int x;
    final class C implements ActionMode.Callback {
        public boolean onCreateActionMode(ActionMode m, Menu menu) { m.setTitle(x + " 项"); return true; }
        public boolean onPrepareActionMode(ActionMode m, Menu menu) { return true; }
        public boolean onActionItemClicked(ActionMode m, MenuItem i) { return true; }
        public void onDestroyActionMode(ActionMode m) { }
    }
}
