import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
public final class T2 implements ActionMode.Callback {
    public boolean onCreateActionMode(ActionMode m, Menu menu) { m.setTitle(3 + " 项"); return true; }
    public boolean onPrepareActionMode(ActionMode m, Menu menu) { return true; }
    public boolean onActionItemClicked(ActionMode m, MenuItem i) { return true; }
    public void onDestroyActionMode(ActionMode m) { }
}
