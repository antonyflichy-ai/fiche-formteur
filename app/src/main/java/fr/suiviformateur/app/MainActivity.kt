package fr.suiviformateur.app

import android.app.*
import android.os.*
import android.content.*
import android.database.sqlite.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.*
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.io.File

class LocalDb(ctx:Context):SQLiteOpenHelper(ctx,"suivi_v2.db",null,1){
 override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE jours(date TEXT PRIMARY KEY, data TEXT NOT NULL)");db.execSQL("CREATE TABLE signatures(semaine TEXT PRIMARY KEY, image TEXT NOT NULL)")}
 override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){}
 fun day(d:String):JSONObject=readableDatabase.rawQuery("SELECT data FROM jours WHERE date=?",arrayOf(d)).use{if(it.moveToFirst())JSONObject(it.getString(0)) else JSONObject()}
 fun put(d:String,j:JSONObject){writableDatabase.execSQL("INSERT OR REPLACE INTO jours(date,data) VALUES(?,?)",arrayOf(d,j.toString()))}
 fun all():List<Pair<String,JSONObject>>{val a=mutableListOf<Pair<String,JSONObject>>();readableDatabase.rawQuery("SELECT date,data FROM jours",null).use{while(it.moveToNext())a.add(it.getString(0) to JSONObject(it.getString(1)))};return a}
 fun signature(w:String):String?=readableDatabase.rawQuery("SELECT image FROM signatures WHERE semaine=?",arrayOf(w)).use{if(it.moveToFirst())it.getString(0) else null}
 fun putSignature(w:String,s:String?){if(s==null)writableDatabase.delete("signatures","semaine=?",arrayOf(w)) else writableDatabase.execSQL("INSERT OR REPLACE INTO signatures(semaine,image) VALUES(?,?)",arrayOf(w,s))}
}
class SignPad(ctx:Context):View(ctx){
 private val strokes=mutableListOf<Path>();private var path:Path?=null
 private val paint=Paint(3).apply{color=Color.rgb(27,69,139);strokeWidth=5f;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
 private var previous:Bitmap?=null
 var onEdited:(()->Unit)?=null
 override fun onDraw(c:Canvas){c.drawColor(Color.WHITE);previous?.let{c.drawBitmap(it,null,Rect(0,0,width,height),null)};strokes.forEach{c.drawPath(it,paint)}}
 override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->{path=Path().apply{moveTo(e.x,e.y)};strokes.add(path!!)};MotionEvent.ACTION_MOVE->path?.lineTo(e.x,e.y);MotionEvent.ACTION_UP->{path?.lineTo(e.x,e.y);path=null;onEdited?.invoke()}};invalidate();return true}
 fun clear(){strokes.clear();previous=null;invalidate();onEdited?.invoke()}
 fun load(s:String?){strokes.clear();previous=null;if(!s.isNullOrBlank())try{val bytes=android.util.Base64.decode(s,0);previous=BitmapFactory.decodeByteArray(bytes,0,bytes.size)}catch(_:Exception){};invalidate()}
 fun encode():String?{if(previous==null&&strokes.isEmpty())return null;val b=Bitmap.createBitmap(width.coerceAtLeast(1),height.coerceAtLeast(1),Bitmap.Config.ARGB_8888);draw(Canvas(b));val o=ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,o);return android.util.Base64.encodeToString(o.toByteArray(),android.util.Base64.NO_WRAP)}
}
class ReminderReceiver:android.content.BroadcastReceiver(){
 override fun onReceive(ctx:Context,intent:Intent){
  val prefs=ctx.getSharedPreferences("options",0)
  if(intent.action=="fr.suiviformateur.REMINDER"){
   if(prefs.getBoolean("vacances",false)||!prefs.getBoolean("rappel",true))return
   val manager=ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
   if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel("rappels","Rappels de saisie",NotificationManager.IMPORTANCE_DEFAULT))
   val open=Intent(ctx,MainActivity::class.java).apply{putExtra("today",true);flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP}
   val pending=PendingIntent.getActivity(ctx,1,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
   val n=Notification.Builder(ctx,"rappels").setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("Suivi Formateur").setContentText("Pense à remplir ta fiche du jour.").setContentIntent(pending).setAutoCancel(true).build()
   manager.notify(101,n)
   MainActivity.scheduleReminder(ctx)
  }else if(intent.action==Intent.ACTION_BOOT_COMPLETED)MainActivity.scheduleReminder(ctx)
 }
}
class MainActivity:Activity(){
 companion object{
  fun scheduleReminder(ctx:Context){
   val p=ctx.getSharedPreferences("options",0);val alarm=ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
   val i=Intent(ctx,ReminderReceiver::class.java).apply{action="fr.suiviformateur.REMINDER"}
   val pi=PendingIntent.getBroadcast(ctx,2,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
   alarm.cancel(pi);if(!p.getBoolean("rappel",true)||p.getBoolean("vacances",false))return
   val t=p.getString("heure","18:00")?:"18:00";val v=t.split(":");val c=Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,v.getOrNull(0)?.toIntOrNull()?.coerceIn(0,23)?:18);set(Calendar.MINUTE,v.getOrNull(1)?.toIntOrNull()?.coerceIn(0,59)?:0);set(Calendar.SECOND,0);if(timeInMillis<=System.currentTimeMillis())add(Calendar.DATE,1)}
   alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,c.timeInMillis,pi)
  }
 }
 private lateinit var db:LocalDb
 private val prefs by lazy{getSharedPreferences("options",0)}
 private var date=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
 private var month=date.substring(0,7)
 private var screen="cal"
 private var folder:Uri?=null
 private var pdfBytes:ByteArray?=null
 private var muted=false
 private val blue=Color.rgb(28,70,142)
 private val days=listOf("Lundi","Mardi","Mercredi","Jeudi","Vendredi","Samedi","Dimanche")
 override fun onCreate(b:Bundle?){super.onCreate(b);db=LocalDb(this);folder=prefs.getString("folder",null)?.let{Uri.parse(it)};if(intent.getBooleanExtra("today",false))screen="fiche";scheduleReminder(this);render()}
 private fun parse(s:String):Calendar=Calendar.getInstance().apply{time=SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(s)?:Date()}
 private fun iso(c:Calendar)=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(c.time)
 private fun shift(d:String,n:Int)=iso(parse(d).apply{add(Calendar.DATE,n)})
 private fun monday(d:String):String{val c=parse(d);val offset=(c.get(Calendar.DAY_OF_WEEK)+5)%7;c.add(Calendar.DATE,-offset);return iso(c)}
 private fun titleDate(d:String)=SimpleDateFormat("EEEE d MMMM yyyy",Locale.FRENCH).format(parse(d).time)
 private fun page(title:String):LinearLayout{
  val root=LinearLayout(this).apply{orientation=1;setBackgroundColor(Color.rgb(244,247,252))}
  setContentView(root)
  val header=TextView(this).apply{text="Suivi Formateur  •  "+title;textSize=21f;setTextColor(Color.WHITE);setBackgroundColor(blue);setPadding(22,28,16,28)}
  root.addView(header)
  val sc=ScrollView(this).apply{isFillViewport=true};root.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
  val content=LinearLayout(this).apply{orientation=1;setPadding(18,12,18,28)};sc.addView(content)
  val nav=LinearLayout(this).apply{orientation=0;setBackgroundColor(Color.WHITE)}
  root.addView(nav)
  listOf("Calendrier" to "cal","Fiche" to "fiche","Semaine" to "semaine","Bilan" to "bilan","Options" to "options").forEach{(label,target)->
   val b=Button(this).apply{text=label;textSize=10f;isAllCaps=false;setPadding(0,4,0,4);setOnClickListener{screen=target;render()}}
   nav.addView(b,LinearLayout.LayoutParams(0,60,1f))
  }
  return content
 }
 private fun text(p:LinearLayout,s:String,size:Float=15f){p.addView(TextView(this).apply{text=s;textSize=size;setTextColor(Color.rgb(37,59,91));setPadding(4,8,4,8)})}
 private fun heading(p:LinearLayout,s:String){text(p,s,19f)}
 private fun button(p:LinearLayout,s:String,run:()->Unit){p.addView(Button(this).apply{text=s;isAllCaps=false;setOnClickListener{run()}})}
 private fun input(p:LinearLayout,label:String,value:String,change:(String)->Unit):EditText{
  text(p,label,13f);val e=EditText(this).apply{setSingleLine(true);setText(value);hint=label;textSize=16f}
  p.addView(e);e.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){if(!muted)change(s.toString())};override fun afterTextChanged(s:Editable?) {}});return e
 }
 private fun check(p:LinearLayout,label:String,value:Boolean,change:(Boolean)->Unit){p.addView(CheckBox(this).apply{text=label;isChecked=value;setOnCheckedChangeListener{_,v->change(v)}})}
 private fun save(d:String,key:String,v:Any){val j=db.day(d);j.put(key,v);db.put(d,j);backup(d,j)}
 private fun backup(d:String,j:JSONObject){val f=folder?:return;try{val dir=android.provider.DocumentsContract.buildDocumentUriUsingTree(f,android.provider.DocumentsContract.getTreeDocumentId(f));val resolver=contentResolver
   val children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(f,android.provider.DocumentsContract.getTreeDocumentId(f))
   var existing:Uri?=null
   resolver.query(children,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{while(it.moveToNext()){if(it.getString(1)=="fiche_"+d+".json")existing=android.provider.DocumentsContract.buildDocumentUriUsingTree(f,it.getString(0))}}
   val target=existing?:android.provider.DocumentsContract.createDocument(resolver,dir,"application/json","fiche_"+d+".json")
   if(target!=null)resolver.openOutputStream(target!!,"wt")?.use{it.write(j.toString(2).toByteArray(Charsets.UTF_8))}
  }catch(e:Exception){runOnUiThread{Toast.makeText(this,"Erreur sauvegarde : "+e.message,Toast.LENGTH_LONG).show()}}
 }
 private fun timeMinutes(s:String):Int?{val m=Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(s.trim())?:return null;val h=m.groupValues[1].toInt();val mi=m.groupValues[2].toInt();return if(h<24&&mi<60)h*60+mi else null}
 private fun pauseMinutes(s:String):Int{val t=s.trim().replace(',','.');val m=Regex("^(\\d+(?:\\.\\d+)?)h(\\d{1,2})?$").matchEntire(t);return if(m!=null)(m.groupValues[1].toDouble()*60).toInt()+(m.groupValues[2].toIntOrNull()?:0) else timeMinutes(t)?:0}
 private fun manual(j:JSONObject):Int{if(!j.optBoolean("manuel")||j.optBoolean("conge"))return 0;val a=timeMinutes(j.optString("debut"))?:return 0;val b=timeMinutes(j.optString("fin"))?:return 0;return ((if(b<a)b+1440 else b)-a-pauseMinutes(j.optString("repos"))).coerceAtLeast(0)}
 private fun hm(n:Int)="${n/60} h ${String.format(Locale.FRANCE,"%02d",n%60)}"
 private data class Totals(val mins:Int,val hotel:Int,val cabine:Int,val rh:Int)
 private fun totals(rows:List<Pair<String,JSONObject>>):Totals{var a=0;var h=0;var c=0;var r=0;rows.forEach{(_,j)->a+=manual(j);if(j.optBoolean("hotel"))h++;if(j.optBoolean("cabine"))c++;if(j.optBoolean("rh"))r++};return Totals(a,h,c,r)}
 private fun summary(p:LinearLayout,t:Totals){text(p,"Heures manuelles : "+hm(t.mins));text(p,"Découchers hôtel : "+t.hotel);text(p,"Découchers cabine : "+t.cabine);text(p,"Nombre de primes RH : "+t.rh)}
 private fun render(){when(screen){"fiche"->fiche();"semaine"->semaine();"bilan"->bilan();"options"->options();else->calendrier()}}
 private fun calendrier(){
  val p=page("Calendrier");heading(p,month)
  val c=parse(month+"-01");button(p,"◀ Mois précédent"){c.add(Calendar.MONTH,-1);month=iso(c).substring(0,7);render()}
  button(p,"Mois suivant ▶"){c.add(Calendar.MONTH,1);month=iso(c).substring(0,7);render()}
  val row=LinearLayout(this).apply{orientation=1};p.addView(row)
  val first=parse(month+"-01");val daysInMonth=first.getActualMaximum(Calendar.DAY_OF_MONTH)
  for(start in 1..daysInMonth step 7){val line=LinearLayout(this);row.addView(line);for(n in start..minOf(start+6,daysInMonth)){
   val d=month+"-"+String.format(Locale.US,"%02d",n)
   val b=Button(this).apply{text=n.toString()+(if(db.day(d).length()>0)" •" else "");textSize=12f;setOnClickListener{date=d;screen="fiche";render()}}
   line.addView(b,LinearLayout.LayoutParams(0,55,1f))
  }}
  button(p,"Ouvrir aujourd'hui"){date=iso(Calendar.getInstance());month=date.substring(0,7);screen="fiche";render()}
  button(p,"Voir la semaine complète"){screen="semaine";render()}
 }
 private fun fiche(){
  val p=page("Fiche journalière");heading(p,titleDate(date))
  button(p,"◀ Jour précédent"){date=shift(date,-1);month=date.substring(0,7);render()}
  button(p,"Jour suivant ▶"){date=shift(date,1);month=date.substring(0,7);render()}
  val j=db.day(date)
  input(p,"Nom",j.optString("nom","FLICHY")){save(date,"nom",it)}
  input(p,"Prénom",j.optString("prenom","ANTONY")){save(date,"prenom",it)}
  heading(p,"Types de saisie — choix cumulables")
  check(p,"Carte conducteur",j.optBoolean("conducteur")){save(date,"conducteur",it)}
  check(p,"Carte de pointage",j.optBoolean("pointage")){save(date,"pointage",it)}
  check(p,"Heures manuelles",j.optBoolean("manuel")){save(date,"manuel",it);render()}
  check(p,"Congé / Repos",j.optBoolean("conge")){save(date,"conge",it);render()}
  if(j.optBoolean("manuel")&&!j.optBoolean("conge")){
   heading(p,"Saisie des heures manuelles")
   input(p,"Début de service (06:00)",j.optString("debut")){save(date,"debut",it)}
   input(p,"Fin de service (17:30)",j.optString("fin")){save(date,"fin",it)}
   input(p,"Temps de repos (1h)",j.optString("repos")){save(date,"repos",it)}
   text(p,"Total calculé à la consultation du bilan : "+hm(manual(j)))
  }
  input(p,"Véhicule",j.optString("vehicule")){save(date,"vehicule",it)}
  input(p,"Équipier / Apparente",j.optString("equipier")){save(date,"equipier",it)}
  input(p,"Motif",j.optString("motif")){save(date,"motif",it)}
  heading(p,"Découchers")
  check(p,"Découcher hôtel",j.optBoolean("hotel")){save(date,"hotel",it);render()}
  if(j.optBoolean("hotel"))input(p,"Nom et lieu hôtel",j.optString("lieu_hotel")){save(date,"lieu_hotel",it)}
  check(p,"Découcher cabine",j.optBoolean("cabine")){save(date,"cabine",it);render()}
  if(j.optBoolean("cabine"))input(p,"Lieu du découcher cabine",j.optString("lieu_cabine")){save(date,"lieu_cabine",it)}
  heading(p,"NE PAS PAYEUR")
  listOf("Petit-déj" to "petit_dej","Déjeuner" to "dejeuner","Dîner" to "diner","Nuit" to "nuit").forEach{(label,key)->check(p,label,j.optBoolean(key)){save(date,key,it)}}
  heading(p,"Prime RH")
  check(p,"Prime RH (1 prime)",j.optBoolean("rh")){save(date,"rh",it)}
  heading(p,"Frais / dépenses")
  listOf("Repas" to "frais_repas","Hébergement" to "frais_hebergement","Transport" to "frais_transport","Autres frais" to "frais_autres").forEach{(label,key)->input(p,label,j.optString(key)){save(date,key,it)}}
  input(p,"Commentaires",j.optString("commentaires")){save(date,"commentaires",it)}
  text(p,"✓ Chaque modification est enregistrée immédiatement sur le téléphone.",12f)
  button(p,"Voir la semaine complète"){screen="semaine";render()}
  button(p,"Exporter la fiche du jour en PDF"){exportPdf(false)}
  button(p,"Partager la fiche via GMX"){sharePdf(false)}
 }
 private fun semaine(){
  val p=page("Semaine complète");val w=monday(date);heading(p,"Du "+w+" au "+shift(w,6))
  button(p,"◀ Semaine précédente"){date=shift(date,-7);month=date.substring(0,7);render()}
  button(p,"Semaine suivante ▶"){date=shift(date,7);month=date.substring(0,7);render()}
  for(i in 0..6){val d=shift(w,i);val j=db.day(d);heading(p,days[i]+" "+d)
   text(p,"Carte conducteur : "+if(j.optBoolean("conducteur"))"Oui" else "Non")
   text(p,"Carte de pointage : "+if(j.optBoolean("pointage"))"Oui" else "Non")
   text(p,"Heures manuelles : "+hm(manual(j)))
   text(p,"Découcher hôtel : "+if(j.optBoolean("hotel"))"Oui" else "Non")
   text(p,"Découcher cabine : "+if(j.optBoolean("cabine"))"Oui" else "Non")
   text(p,"Prime RH : "+if(j.optBoolean("rh"))"1" else "0")
   text(p,"Véhicule : "+j.optString("vehicule","—")+" • Motif : "+j.optString("motif","—"),13f)
   button(p,"Modifier "+days[i]){date=d;month=d.substring(0,7);screen="fiche";render()}
  }
  heading(p,"Récapitulatif de la semaine")
  summary(p,totals(db.all().filter{monday(it.first)==w}))
  heading(p,"Signature de fin de semaine uniquement")
  val pad=SignPad(this);p.addView(pad,LinearLayout.LayoutParams(-1,220))
  pad.load(db.signature(w))
  pad.onEdited={db.putSignature(w,pad.encode())}
  button(p,"Effacer la signature hebdomadaire"){pad.clear()}
  button(p,"Exporter la semaine signée en PDF"){exportPdf(true)}
  button(p,"Partager la semaine via GMX"){sharePdf(true)}
 }
 private fun bilan(){
  val p=page("Bilan mensuel");heading(p,month)
  val t=totals(db.all().filter{it.first.startsWith(month)});summary(p,t)
  button(p,"◀ Mois précédent"){val c=parse(month+"-01");c.add(Calendar.MONTH,-1);month=iso(c).substring(0,7);render()}
  button(p,"Mois suivant ▶"){val c=parse(month+"-01");c.add(Calendar.MONTH,1);month=iso(c).substring(0,7);render()}
 }
 private fun options(){
  val p=page("Options");heading(p,"Sauvegarde automatique")
  text(p,"Les modifications sont enregistrées immédiatement en local. Choisis un dossier pour obtenir aussi une copie JSON de chaque fiche.")
  text(p,"Dossier : "+(folder?.toString()?:"Non configuré"),12f)
  button(p,"Choisir le dossier de sauvegarde"){val i=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(i,124)}
  heading(p,"Rappels")
  check(p,"Rappel quotidien",prefs.getBoolean("rappel",true)){prefs.edit().putBoolean("rappel",it).apply();scheduleReminder(this)}
  input(p,"Heure du rappel (HH:mm)",prefs.getString("heure","18:00")?:"18:00"){prefs.edit().putString("heure",it).apply();if(timeMinutes(it)!=null)scheduleReminder(this)}
  check(p,"Mode vacances : suspendre les rappels",prefs.getBoolean("vacances",false)){prefs.edit().putBoolean("vacances",it).apply();scheduleReminder(this)}
  check(p,"Bilan de fin de mois",prefs.getBoolean("bilan",true)){prefs.edit().putBoolean("bilan",it).apply()}
  text(p,"Les notifications Android nécessitent l'autorisation système. Le bilan mensuel est consultable depuis l'onglet Bilan.",12f)
  if(Build.VERSION.SDK_INT>=33)button(p,"Autoriser les notifications"){requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"),55)}
 }
 private fun pdf(weekly:Boolean):ByteArray{
  val doc=PdfDocument();val p=doc.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val c=p.canvas
  val pen=Paint(3).apply{color=Color.rgb(28,70,142);textSize=17f;typeface=Typeface.DEFAULT_BOLD}
  c.drawText("SUIVI FORMATEUR",30f,42f,pen);pen.color=Color.DKGRAY;pen.textSize=12f;pen.typeface=Typeface.DEFAULT
  var y=70f
  fun line(s:String){if(y>690f)return;c.drawText(s.replace("\n"," ").take(92),30f,y,pen);y+=21f}
  if(weekly){val w=monday(date);line("Semaine du "+w+" au "+shift(w,6));for(i in 0..6){val d=shift(w,i);val j=db.day(d);line(days[i]+" "+d+" : "+hm(manual(j))+" | RH "+(if(j.optBoolean("rh"))1 else 0)+" | Hôtel "+(if(j.optBoolean("hotel"))1 else 0)+" | Cabine "+(if(j.optBoolean("cabine"))1 else 0));line("  Véhicule : "+j.optString("vehicule")+" | Motif : "+j.optString("motif"))}
   val t=totals(db.all().filter{monday(it.first)==w});line("Total manuel : "+hm(t.mins)+" | Hôtel : "+t.hotel+" | Cabine : "+t.cabine+" | RH : "+t.rh)
   line("Signature hebdomadaire :")
   db.signature(w)?.let{try{val b=android.util.Base64.decode(it,0);val bitmap=BitmapFactory.decodeByteArray(b,0,b.size);if(bitmap!=null)c.drawBitmap(bitmap,null,Rect(150,y.toInt(),420,(y+90).toInt()),null)}catch(_:Exception){}}
  }else{val j=db.day(date);line("Fiche du "+date+" | "+j.optString("nom","FLICHY")+" "+j.optString("prenom","ANTONY"))
   listOf("conducteur" to "Carte conducteur","pointage" to "Carte de pointage","manuel" to "Heures manuelles","conge" to "Congé / Repos").forEach{(k,l)->line(l+" : "+if(j.optBoolean(k))"Oui" else "Non")}
   line("Début : "+j.optString("debut")+" | Fin : "+j.optString("fin")+" | Repos : "+j.optString("repos"))
   line("Total manuel : "+hm(manual(j)));listOf("vehicule" to "Véhicule","equipier" to "Équipier","motif" to "Motif","lieu_hotel" to "Hôtel","lieu_cabine" to "Découcher cabine","commentaires" to "Commentaires").forEach{(k,l)->line(l+" : "+j.optString(k))}
   line("Découcher hôtel : "+j.optBoolean("hotel")+" | Découcher cabine : "+j.optBoolean("cabine")+" | Prime RH : "+(if(j.optBoolean("rh"))1 else 0))
  }
  doc.finishPage(p);val o=ByteArrayOutputStream();doc.writeTo(o);doc.close();return o.toByteArray()
 }
 private fun exportPdf(weekly:Boolean){pdfBytes=pdf(weekly);val name=if(weekly)"semaine_"+monday(date)+".pdf" else "fiche_"+date+".pdf";val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/pdf";putExtra(Intent.EXTRA_TITLE,name)};startActivityForResult(i,123)}
 private fun sharePdf(weekly:Boolean){try{val name=if(weekly)"semaine_"+monday(date)+".pdf" else "fiche_"+date+".pdf";val f=File(cacheDir,"shared_pdfs").apply{mkdirs()}.resolve(name);f.writeBytes(pdf(weekly));val u=FileProvider.getUriForFile(this,packageName+".fileprovider",f);val i=Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,u);putExtra(Intent.EXTRA_SUBJECT,"Suivi Formateur");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=ClipData.newUri(contentResolver,"PDF",u)};startActivity(Intent.createChooser(i,"Partager avec GMX"))}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}}
 override fun onActivityResult(requestCode:Int,resultCode:Int,result:Intent?){super.onActivityResult(requestCode,resultCode,result);if(resultCode!=RESULT_OK)return
  if(requestCode==123){try{val u=result?.data?:return;contentResolver.openOutputStream(u)?.use{it.write(pdfBytes?:return)};Toast.makeText(this,"PDF enregistré",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}finally{pdfBytes=null}}
  if(requestCode==124){val u=result?.data?:return;try{contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);folder=u;prefs.edit().putString("folder",u.toString()).apply();db.all().forEach{backup(it.first,it.second)};Toast.makeText(this,"Dossier configuré",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()};render()}
 }
}
