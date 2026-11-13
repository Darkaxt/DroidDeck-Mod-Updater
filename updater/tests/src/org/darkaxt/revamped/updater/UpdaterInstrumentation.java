package org.darkaxt.revamped.updater;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.Future;
import java.util.Arrays;

public final class UpdaterInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments=arguments; start(); }
    private static final class Interrupted extends RuntimeException { }
    private static void require(boolean value,String message) { SafGame.check(value,"TEST: "+message); }
    @Override public void onStart() {
        JSONObject report=new JSONObject(); Bundle result=new Bundle();
        try {
            String mode=arguments.getString("mode","ui-check"); long started=System.nanoTime();
            if(mode.equals("recovery")) recovery(report);
            else if(mode.equals("rune-reset")) runeReset(report);
            else if(mode.equals("delivery-audit")) audit(report);
            else if(mode.equals("warhammer")) warhammer(report);
            else if(mode.equals("warhammer-live")) warhammerLive(report);
            else ui(mode,report);
            report.put("ok",true).put("elapsed_ms",(System.nanoTime()-started)/1000000); result.putString("report",report.toString()); finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) { try{report.put("ok",false).put("error",failure.toString());}catch(Exception ignored){} result.putString("report",report.toString()); finish(Activity.RESULT_CANCELED,result); }
    }
    private void preserved(android.content.Context context,Uri tree,String path,JSONObject hashes)throws Exception {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,path);
        try(android.database.Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            require(cursor!=null,"preserved folder listing");
            while(cursor.moveToNext()){
                String id=cursor.getString(0);
                if(cursor.getString(1).equals(DocumentsContract.Document.MIME_TYPE_DIR))preserved(context,tree,id,hashes);
                else if(!cursor.getString(2).endsWith(".log"))try(java.io.InputStream input=context.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree,id))){hashes.put(id,SafGame.hash(input));}
            }
        }
    }
    private void audit(JSONObject report)throws Exception {
        android.content.Context context=getTargetContext();android.content.SharedPreferences prefs=context.getSharedPreferences("MainActivity",0);
        Uri tree=Uri.parse(prefs.getString("gameTree",""));String root=prefs.getString("gameDocumentId","");SafGame game=new SafGame(context.getContentResolver(),tree,root);
        JSONObject installed=UpdateEngine.installed(game);JSONArray files=installed.getJSONArray("files");
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);require(game.hash(file.getString("path")).equals(file.getString("sha256")),"delivered member "+file.getString("path"));}
        JSONObject stock;try(java.io.InputStream input=context.getAssets().open("supported-windows.json")){stock=new JSONObject(new String(SafGame.bytes(input),"UTF-8"));}
        for(java.util.Iterator<String> keys=stock.keys();keys.hasNext();){String name=keys.next();require(game.hash(name.replace('\\','/')).equals(stock.getString(name)),"stock original "+name);}
        String app=context.getPackageManager().getApplicationInfo("com.droiddeck.launcher",0).dataDir;
        String prefix=app+"/files/linuxfs/root/.local/share/Steam/steamapps/compatdata/1966900/pfx";JSONObject hashes=new JSONObject();
        preserved(context,tree,prefix+"/drive_c/users/steamuser/AppData/LocalLow/Flanne",hashes);preserved(context,tree,root+"/BepInEx/config",hashes);
        for(String leaf:new String[]{"user.reg","system.reg","userdef.reg"})try(java.io.InputStream input=context.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree,prefix+"/"+leaf))){hashes.put(prefix+"/"+leaf,SafGame.hash(input));}
        require(!new UpdateEngine(context).pending(),"no pending transaction");
        for(String path:new String[]{SafGame.PLUGIN,SafGame.MANIFEST})require(!game.exists(path+".revamped-next")&&!game.exists(path+".revamped-previous"),"no deployment leftovers");
        report.put("version",installed.getString("version")).put("source_commit",installed.getString("source_commit")).put("owned_verified",files.length()).put("stock_verified",stock.length()).put("preserved",hashes).put("detected_root",root).put("tree",tree.toString());
    }
    private void capture(Activity activity,JSONObject report,String key)throws Exception {
        runOnMainSync(()->{try{
            View decor=activity.getWindow().getDecorView();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            decor.draw(new android.graphics.Canvas(bitmap));java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();
            if(arguments.getString("capture_files")!=null){
                java.io.File file=new java.io.File(activity.getExternalFilesDir(null),key+".png");
                try(java.io.OutputStream output=new java.io.FileOutputStream(file)){output.write(bytes.toByteArray());}
                report.put(key,file.getAbsolutePath());
            } else report.put(key,android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));
        }catch(Exception failure){throw new RuntimeException(failure);}});
    }
    private GitHubRelease fixtureRelease(SafGame game,String version,String code,boolean wrongAsset)throws Exception {
        JSONArray rows=UpdateEngine.installed(game).getJSONArray("files"); JSONObject files=new JSONObject();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);files.put(row.getString("path"),row.getString("sha256"));}
        byte[] bytes=code.getBytes("UTF-8");files.put(SafGame.PLUGIN,SafGame.hash(bytes));
        if(wrongAsset)files.put(SafGame.MOD+"Assets/fixture.json",SafGame.hash("different-assets".getBytes("UTF-8")));
        return new GitHubRelease(version,"synthetic recovery fixture","2222222222222222222222222222222222222222","fixture",new JSONObject().put("files",files),bytes);
    }
    private void recovery(JSONObject report)throws Exception {
        String name=arguments.getString("fixture_name");require(name!=null,"prepared fixture required");
        // Starting instrumentation force-stops its target and revokes temporary
        // URI grants. Prepare and grant the isolated fixture after that event.
        java.util.concurrent.CountDownLatch prepared=new java.util.concurrent.CountDownLatch(1); Bundle preparation=new Bundle();
        android.os.ResultReceiver receiver=new android.os.ResultReceiver(null){@Override protected void onReceiveResult(int code,Bundle data){preparation.putBoolean("ok",code==1);preparation.putAll(data);prepared.countDown();}};
        Intent prepare=new Intent().setClassName("org.darkaxt.revamped.updater.tests",PrepareFixture.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture_name",name).putExtra("receiver",receiver);
        getContext().startActivity(prepare);prepared.await();require(preparation.getBoolean("ok"),"fixture prepared: "+preparation.getString("error"));
        SafGame game=new SafGame(getTargetContext().getContentResolver(),DocumentsContract.buildTreeDocumentUri(FixtureProvider.AUTHORITY,name));
        require(GameLocator.detect(getTargetContext(),game.tree).size()==1,"a directly selected game folder is detected");
        UpdateEngine engine=new UpdateEngine(getTargetContext());require(!engine.pending(),"preserve an existing deployment journal");
        String marker=game.hash("saves-marker.json"),stock=game.hash("MinutesTillDawn.exe");
        GitHubRelease first=fixtureRelease(game,"1.1.3","next-code",false);
        try { engine.deploy(game,first,message->{if(message.equals("Installing verified plugin…"))throw new Interrupted();});throw new AssertionError("Expected staged interruption"); }catch(Interrupted expected){}
        require(engine.pending(),"journal records interrupted staging");game.putNew(SafGame.PLUGIN+".revamped-next",Arrays.copyOf(first.plugin,2));
        MainActivity interrupted=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(interrupted);
        capture(interrupted,report,"interrupted_ui_png");runOnMainSync(()->interrupted.finish());
        engine.finish(message->{});require(UpdateEngine.installed(game).getString("version").equals("1.1.3"),"partial staged write recovers to the recorded payload");
        GitHubRelease second=fixtureRelease(game,"1.1.4","next-next-code",false);
        try { engine.deploy(game,second,message->{if(message.equals("Updating installation record…"))throw new Interrupted();});throw new AssertionError("Expected manifest-boundary interruption"); }catch(Interrupted expected){}
        require(engine.pending()&&game.hash(SafGame.PLUGIN).equals(SafGame.hash(second.plugin))&&UpdateEngine.installed(game).getString("version").equals("1.1.3"),"real plugin activation precedes pending manifest commit");
        engine.finish(message->{});require(UpdateEngine.installed(game).getString("version").equals("1.1.4"),"manifest-boundary interruption finishes deterministically");
        String before=game.hash(SafGame.MANIFEST);
        try { engine.deploy(game,fixtureRelease(game,"1.1.5","unsupported-code",true),message->{});throw new AssertionError("Expected asset requirement"); }catch(IllegalStateException expected){require(expected.getMessage().contains("prepared assets"),"concrete asset incompatibility reported");}
        require(!engine.pending()&&game.hash(SafGame.MANIFEST).equals(before),"unsupported assets cause no deployment");
        require(game.hash("saves-marker.json").equals(marker)&&game.hash("MinutesTillDawn.exe").equals(stock),"unowned save/stock markers preserved");
        SafGame delivered=new SafGame(getTargetContext().getContentResolver(),game.tree);
        for(String path:new String[]{SafGame.PLUGIN,SafGame.MANIFEST})require(!delivered.exists(path+".revamped-next")&&!delivered.exists(path+".revamped-previous"),"no completed transaction leftovers");
        report.put("fixture_only",true).put("recovery_after_partial_write",true).put("recovery_after_plugin_activation",true).put("assets_rejected_before_write",true).put("unowned_preserved",true);
    }
    private void runeReset(JSONObject report)throws Exception {
        String name=arguments.getString("fixture_name");require(name!=null,"isolated fixture required");
        java.util.concurrent.CountDownLatch prepared=new java.util.concurrent.CountDownLatch(1);Bundle preparation=new Bundle();
        android.os.ResultReceiver receiver=new android.os.ResultReceiver(null){@Override protected void onReceiveResult(int code,Bundle data){preparation.putBoolean("ok",code==1);preparation.putAll(data);prepared.countDown();}};
        getContext().startActivity(new Intent().setClassName("org.darkaxt.revamped.updater.tests",PrepareFixture.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture_name",name).putExtra("rune_layout",true).putExtra("receiver",receiver));
        prepared.await();require(preparation.getBoolean("ok"),"fixture preparation: "+preparation.getString("error"));
        android.content.Context context=getTargetContext();Uri tree=DocumentsContract.buildTreeDocumentUri(FixtureProvider.AUTHORITY,name);
        SafGame game=new SafGame(context.getContentResolver(),tree,name+"/library/steamapps/common/20MinuteTillDawn");SafGame granted=new SafGame(context.getContentResolver(),tree);
        UpdateEngine engine=new UpdateEngine(context);require(!engine.pending(),"no unrelated journal");
        GitHubRelease release=fixtureRelease(game,"1.3.0","rune-code",false);release.update.put("save_epoch",1);
        JSONObject plan=SaveTransition.plan(context,game);require(plan.getJSONArray("files").length()==6,"all six native/companion/partial saves recorded");
        String prefix="library/steamapps/compatdata/1966900/pfx/",profile=prefix+"drive_c/users/steamuser/AppData/LocalLow/Flanne/MinutesTillDawn/";
        String[] preserved={prefix+"user.reg",profile+"notes.txt",prefix+"drive_c/users/steamuser/AppData/LocalLow/Flanne/OtherGame/gamedata.json","library/steamapps/compatdata/123456/pfx/drive_c/users/steamuser/AppData/LocalLow/Flanne/MinutesTillDawn/gamedata.json","library/steamapps/common/20MinuteTillDawn/BepInEx/config/revamped.cfg"};
        JSONObject hashes=new JSONObject();for(String path:preserved)hashes.put(path,granted.hash(path));
        try { SaveTransition.plan(context,new SafGame(context.getContentResolver(),DocumentsContract.buildTreeDocumentUri(FixtureProvider.AUTHORITY,game.rootId)));throw new AssertionError("narrow grant must require reconnection"); }catch(IllegalStateException expected){require(expected.getMessage().contains("Reconnect"),"game-only grant rejected before mutations");}
        context.getSharedPreferences("MainActivity",0).edit().putString("gameTree",tree.toString()).putString("gameDocumentId",game.rootId).commit();
        MainActivity activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(activity);
        runOnMainSync(()->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("release");field.setAccessible(true);field.set(activity,release);java.lang.reflect.Method render=MainActivity.class.getDeclaredMethod("renderButtons");render.setAccessible(true);render.invoke(activity);}catch(Exception error){throw new RuntimeException(error);}});
        require(((Button)activity.findViewById(MainActivity.DEPLOY_BUTTON)).getText().toString().contains("Reset saves"),"native action announces reset");capture(activity,report,"reset_warning_png");runOnMainSync(()->activity.finish());
        try { engine.deploy(game,release,message->{if(message.equals("Resetting old game saves 1 / 6"))throw new Interrupted();});throw new AssertionError("expected reset interruption"); }catch(Interrupted expected){}
        require(engine.pending()&&game.hash(SafGame.PLUGIN).equals(SafGame.hash("previous-code".getBytes("UTF-8"))),"reset interruption keeps old plugin and recorded journal");
        new UpdateEngine(context).finish(message->{});
        require(UpdateEngine.installed(new SafGame(context.getContentResolver(),tree,game.rootId)).getInt("rune_save_epoch")==1,"persistent installed transition marker");
        SafGame after=new SafGame(context.getContentResolver(),tree);for(String path:preserved)require(after.hash(path).equals(hashes.getString(path)),"preserve unrelated member "+path);
        SafGame profileGame=GameLocator.at(context,after,plan.getString("prefix"));JSONArray deleted=plan.getJSONArray("files");for(int i=0;i<deleted.length();i++)require(!profileGame.exists(deleted.getJSONObject(i).getString("path")),"old save absent");
        Uri directory=after.find(profile.substring(0,profile.length()-1));Uri fresh=DocumentsContract.createDocument(context.getContentResolver(),directory,"application/json","gamedata.json");try(java.io.OutputStream output=context.getContentResolver().openOutputStream(fresh,"wt")){output.write("new-player-progress".getBytes("UTF-8"));}
        SafGame current=new SafGame(context.getContentResolver(),tree,game.rootId);GitHubRelease later=fixtureRelease(current,"1.3.1","later-code",false);later.update.put("save_epoch",1);
        new UpdateEngine(context).deploy(current,later,message->{});new UpdateEngine(context).deploy(new SafGame(context.getContentResolver(),tree,game.rootId),later,message->{});
        require(new SafGame(context.getContentResolver(),tree).hash(profile+"gamedata.json").equals(SafGame.hash("new-player-progress".getBytes("UTF-8"))),"later/repeated update preserves new progress");
        require(!new UpdateEngine(context).pending(),"completed transactions removed");
        report.put("fixture_only",true).put("save_files_deleted",6).put("interrupted_reset_recovered",true).put("unrelated_files_preserved",preserved.length).put("later_and_repeat_preserve_progress",true).put("narrow_grant_requires_reconnect",true).put("persistent_epoch",1);
    }
    private WarhammerRelease warhammerRelease(String version,String[][] members)throws Exception {
        java.io.File payload=new java.io.File(getTargetContext().getCacheDir(),"fixture-payload-"+version);WarhammerRelease.delete(payload);require(payload.mkdirs(),"payload folder");
        JSONObject files=new JSONObject();
        for(String[] member:members){byte[] bytes=member[1].getBytes("UTF-8");String sha=SafGame.hash(bytes);files.put(member[0],sha);try(java.io.OutputStream output=new java.io.FileOutputStream(new java.io.File(payload,sha))){output.write(bytes);}}
        return new WarhammerRelease(version,"synthetic warhammer fixture","3333333333333333333333333333333333333333",WarhammerRelease.LAUNCH,files,payload);
    }
    private void warhammer(JSONObject report)throws Exception {
        String name=arguments.getString("fixture_name");require(name!=null,"isolated fixture required");
        java.util.concurrent.CountDownLatch prepared=new java.util.concurrent.CountDownLatch(1);Bundle preparation=new Bundle();
        android.os.ResultReceiver receiver=new android.os.ResultReceiver(null){@Override protected void onReceiveResult(int code,Bundle data){preparation.putBoolean("ok",code==1);preparation.putAll(data);prepared.countDown();}};
        getContext().startActivity(new Intent().setClassName("org.darkaxt.revamped.updater.tests",PrepareFixture.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture_name",name).putExtra("warhammer_layout",true).putExtra("receiver",receiver));
        prepared.await();require(preparation.getBoolean("ok"),"fixture preparation: "+preparation.getString("error"));
        android.content.Context context=getTargetContext();Uri tree=DocumentsContract.buildTreeDocumentUri(FixtureProvider.AUTHORITY,name);
        String root=name;
        java.util.List<SafGame> found=WarhammerEngine.detect(context,tree);
        require(found.size()==1&&found.get(0).rootId.equals(root),"directly selected game folder detected");
        SafGame game=new SafGame(context.getContentResolver(),tree,root);
        require(WarhammerEngine.installed(game)==null,"no record before the first installation");
        String[] keep={"WarhammerSurvivors.exe","GameAssembly.dll","UserData/WHSurvivorsTweaks.cfg","UserLibs/Other.dll"};JSONObject hashes=new JSONObject();for(String path:keep)hashes.put(path,game.hash(path));
        WarhammerEngine engine=new WarhammerEngine(context);require(!engine.pending(),"no unrelated journal");
        WarhammerRelease first=warhammerRelease("1.3.0",new String[][]{{"version.dll","loader-v1"},{"MelonLoader/net6/MelonLoader.dll","ml-core-v1"},{"MelonLoader/Dependencies/Extra.dll","ml-dependency"},{"Mods/WHSurvivorsTweaks.dll","mod-1.3.0"},{"WHSurvivorsTweaks.txt","readme-1.3.0"}});
        try { engine.deploy(game,first,message->{if(message.startsWith("Installing files 1 /"))throw new Interrupted();});throw new AssertionError("expected interruption"); }catch(Interrupted expected){}
        game=new SafGame(context.getContentResolver(),tree,root);require(engine.pending()&&game.exists("MelonLoader/Dependencies/Extra.dll")&&!game.exists("MelonLoader/net6/MelonLoader.dll"),"journaled interruption after the first file");
        java.util.Set<String> staging=new java.util.HashSet<>(java.util.Arrays.asList("MelonLoader/net6/MelonLoader.dll"));
        SafGame partial=new SafGame(context.getContentResolver(),tree,root).owning(staging);partial.mkdirs("MelonLoader/net6");partial.putNew("MelonLoader/net6/MelonLoader.dll.revamped-next","ml-".getBytes("UTF-8"));
        context.getSharedPreferences("MainActivity",0).edit().putString("whTree",tree.toString()).putString("whDocumentId",root).putString("selectedGame","warhammer").commit();
        MainActivity activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(activity);
        String action=((Button)activity.findViewById(MainActivity.DEPLOY_BUTTON)).getText().toString();require(action.contains("Finish interrupted"),"interrupted installation announced: "+action);
        capture(activity,report,"warhammer_interrupted_png");runOnMainSync(()->activity.finish());
        new WarhammerEngine(context).finish(message->{});
        game=new SafGame(context.getContentResolver(),tree,root);JSONObject record=WarhammerEngine.installed(game);
        require(record!=null&&record.getString("version").equals("1.3.0"),"recovered installation recorded");
        JSONArray rows=record.getJSONArray("files");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);require(game.hash(row.getString("path")).equals(row.getString("sha256")),"installed "+row.getString("path"));require(row.getBoolean("owned")!=row.getString("path").equals("version.dll"),"ownership of "+row.getString("path"));}
        WarhammerRelease second=warhammerRelease("1.3.1",new String[][]{{"version.dll","loader-v1"},{"MelonLoader/net6/MelonLoader.dll","ml-core-v1"},{"Mods/WHSurvivorsTweaks.dll","mod-1.3.1"},{"WHSurvivorsTweaks.txt","readme-1.3.0"}});
        new WarhammerEngine(context).deploy(game,second,message->{});game=new SafGame(context.getContentResolver(),tree,root);
        require(!game.exists("MelonLoader/Dependencies/Extra.dll")&&game.hash("Mods/WHSurvivorsTweaks.dll").equals(SafGame.hash("mod-1.3.1".getBytes("UTF-8")))&&WarhammerEngine.installed(game).getString("version").equals("1.3.1"),"update replaces and removes owned files");
        require(game.hash("version.dll").equals(SafGame.hash("loader-v1".getBytes("UTF-8"))),"shared loader untouched");
        String recordHash=game.hash(WarhammerEngine.RECORD);
        WarhammerRelease conflict=warhammerRelease("1.3.2",new String[][]{{"version.dll","loader-v1"},{"MelonLoader/net6/MelonLoader.dll","ml-core-v1"},{"Mods/WHSurvivorsTweaks.dll","mod-1.3.2"},{"WHSurvivorsTweaks.txt","readme-1.3.0"},{"UserLibs/Other.dll","ours"}});
        try { new WarhammerEngine(context).deploy(game,conflict,message->{});throw new AssertionError("expected conflict"); }catch(IllegalStateException expected){require(expected.getMessage().contains("Nothing was changed"),"conflict explained: "+expected.getMessage());}
        game=new SafGame(context.getContentResolver(),tree,root);
        require(!new WarhammerEngine(context).pending()&&game.hash(WarhammerEngine.RECORD).equals(recordHash)&&game.hash("Mods/WHSurvivorsTweaks.dll").equals(SafGame.hash("mod-1.3.1".getBytes("UTF-8"))),"conflict refused before any change");
        new WarhammerEngine(context).deploy(game,second,message->{});require(!new WarhammerEngine(context).pending(),"repeat installation of the same release is a verified no-op");
        game=new SafGame(context.getContentResolver(),tree,root);
        for(String path:keep)require(game.hash(path).equals(hashes.getString(path)),"preserved "+path);
        for(String path:new String[]{"version.dll","MelonLoader/net6/MelonLoader.dll","MelonLoader/Dependencies/Extra.dll","Mods/WHSurvivorsTweaks.dll","WHSurvivorsTweaks.txt",WarhammerEngine.RECORD})require(!game.exists(path+".revamped-next")&&!game.exists(path+".revamped-previous"),"no staging leftovers "+path);
        MainActivity done=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(done);
        runOnMainSync(()->require(((TextView)done.findViewById(MainActivity.INSTALLED)).getText().toString().equals("1.3.1"),"installed version shown"));
        capture(done,report,"warhammer_ui_png");runOnMainSync(()->done.finish());
        report.put("fixture_only",true).put("interrupted_partial_write_recovered",true).put("shared_loader_not_owned",true).put("manual_mod_adopted",true).put("removed_owned_file",true).put("conflict_refused_unchanged",true).put("preserved",keep.length);
    }
    /** Real public GitHub package, installed into an isolated fixture game folder (never a real game). */
    private void warhammerLive(JSONObject report)throws Exception {
        String name=arguments.getString("fixture_name");require(name!=null,"isolated fixture required");
        java.util.concurrent.CountDownLatch prepared=new java.util.concurrent.CountDownLatch(1);Bundle preparation=new Bundle();
        android.os.ResultReceiver receiver=new android.os.ResultReceiver(null){@Override protected void onReceiveResult(int code,Bundle data){preparation.putBoolean("ok",code==1);preparation.putAll(data);prepared.countDown();}};
        getContext().startActivity(new Intent().setClassName("org.darkaxt.revamped.updater.tests",PrepareFixture.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture_name",name).putExtra("warhammer_layout",true).putExtra("receiver",receiver));
        prepared.await();require(preparation.getBoolean("ok"),"fixture preparation: "+preparation.getString("error"));
        android.content.Context context=getTargetContext();Uri tree=DocumentsContract.buildTreeDocumentUri(FixtureProvider.AUTHORITY,name);
        String root=name;
        WarhammerRelease release=WarhammerRelease.fetch(context,message->{});
        require(release.version.equals(arguments.getString("expect_version"))&&release.files.length()==Integer.parseInt(arguments.getString("expect_files")),"live package "+release.version+" with "+release.files.length()+" files");
        // The fixture's unrelated manual mod and loader stand-ins differ from the real ones: the
        // real loader must be refused untouched (another MelonLoader), then installs into a clean folder.
        SafGame game=new SafGame(context.getContentResolver(),tree,root);
        try { new WarhammerEngine(context).deploy(game,release,message->{});throw new AssertionError("expected loader conflict"); }catch(IllegalStateException expected){require(expected.getMessage().contains("version.dll"),"foreign loader refused: "+expected.getMessage());}
        require(!new WarhammerEngine(context).pending()&&WarhammerEngine.installed(game)==null,"refusal changed nothing");
        java.util.Set<String> remove=new java.util.HashSet<>(java.util.Arrays.asList("version.dll"));SafGame owner=new SafGame(context.getContentResolver(),tree,root).owning(remove);owner.deleteExpected("version.dll",game.hash("version.dll"));
        long started=System.nanoTime();
        new WarhammerEngine(context).deploy(new SafGame(context.getContentResolver(),tree,root),release,message->{});
        game=new SafGame(context.getContentResolver(),tree,root);JSONObject record=WarhammerEngine.installed(game);
        require(record!=null&&record.getString("version").equals(release.version)&&record.getJSONArray("files").length()==release.files.length(),"real package installed and recorded");
        for(java.util.Iterator<String> it=release.files.keys();it.hasNext();){String path=it.next();require(game.hash(path).equals(release.files.getString(path)),"installed "+path);}
        require(game.hash("UserData/WHSurvivorsTweaks.cfg").equals(SafGame.hash("keep-settings".getBytes("UTF-8"))),"settings preserved");
        report.put("live_version",release.version).put("live_files",release.files.length()).put("install_ms",(System.nanoTime()-started)/1000000).put("manual_mod_replaced",true).put("foreign_loader_refused",true);
    }
    private void click(MainActivity activity,int id) {
        runOnMainSync(()->{Button button=activity.findViewById(id);require(button.isShown()&&button.isEnabled(),"native action enabled "+id);button.performClick();});
    }
    private void await(MainActivity activity)throws Exception { Future<?> running=activity.running;if(running!=null)running.get();waitForIdleSync(); }
    private void ui(String mode,JSONObject report)throws Exception {
        MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(activity);
        if(mode.equals("ui-check")||mode.equals("ui-deploy")||mode.equals("ui-compact")){click(activity,MainActivity.CHECK_BUTTON);await(activity);}
        if(mode.equals("ui-deploy")){click(activity,MainActivity.DEPLOY_BUTTON);await(activity);}
        if(mode.equals("ui-compact"))runOnMainSync(()->activity.getWindow().setLayout((int)(390*activity.getResources().getDisplayMetrics().density),WindowManager.LayoutParams.MATCH_PARENT));
        waitForIdleSync();
        runOnMainSync(()->{
            try {
                String status=((TextView)activity.findViewById(MainActivity.STATUS)).getText().toString();
                String installed=((TextView)activity.findViewById(MainActivity.INSTALLED)).getText().toString();
                String available=((TextView)activity.findViewById(MainActivity.AVAILABLE)).getText().toString();
                if(mode.equals("ui-check"))require(status.contains("Package verified")&&available.equals("1.1.3"),"real GitHub fetch and package verification");
                if(mode.equals("ui-deploy"))require(status.contains("Update verified")&&installed.equals("1.1.3"),"real native client deploy and version read-back");
                if(mode.equals("ui-inspect"))require(status.contains("Game connected")&&installed.equals("1.1.3"),"remembered folder grant and restored status");
                JSONArray nodes=new JSONArray();dump(activity.getWindow().getDecorView(),nodes);
                report.put("mode",mode).put("installed",installed).put("available",available).put("status",status).put("native_view_tree",nodes).put("density",activity.getResources().getDisplayMetrics().density);
            } catch(Exception failure){throw new RuntimeException(failure);}
        });
        capture(activity,report,"native_ui_png");
    }
    private void dump(View view,JSONArray nodes)throws Exception {
        if(view.isShown()&&(view instanceof TextView||view.getId()==MainActivity.DEPLOY_BUTTON)){
            int[] point=new int[2];view.getLocationOnScreen(point);
            JSONObject node=new JSONObject().put("id",view.getId()).put("type",view.getClass().getSimpleName()).put("enabled",view.isEnabled()).put("x",point[0]).put("y",point[1]).put("width",view.getWidth()).put("height",view.getHeight());
            if(view instanceof TextView)node.put("text",((TextView)view).getText()).put("text_size_px",((TextView)view).getTextSize());nodes.put(node);
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)dump(group.getChildAt(i),nodes);}
    }
}
