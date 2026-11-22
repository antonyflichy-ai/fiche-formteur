package fr.suiviformateur.app

import android.app.*
import android.os.Bundle
import android.content.*
import android.database.sqlite.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

class LocalDb(ctx:Context):SQLiteOpenHelper(ctx,"fiches.db",null,1) {
 override fun onCreate(db:SQLiteDatabase) { db.execSQL("CREATE TABLE fiches(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,data TEXT NOT NULL,signature TEXT,updated INTEGER)") }
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {}
 fun all():List<Pair<Long,String>> { val out=mutableListOf<Pair<Long,String>>();readableDatabase.rawQuery("SELECT id,title FROM fiches ORDER BY updated DESC",null).use { c->while(c.moveToNext())out.add(c.getLong(0) to c.getString(1)) };return out }
 fun get(id:Long):Pair<JSONObject,String?>? { readableDatabase.rawQuery("SELECT data,signature FROM fiches WHERE id=?",arrayOf(id.toString())).use { c->if(c.moveToFirst())return JSONObject(c.getString(0)) to c.getString(1) };return null }
 fun put(id:Long?,title:String,data:JSONObject,signature:String?):Long {val v=ContentValues().apply{put("title",title);put("data",data.toString());put("signature",signature);put("updated",System.currentTimeMillis())};return if(id==null)writableDatabase.insertOrThrow("fiches",null,v) else {writableDatabase.update("fiches",v,"id=?",arrayOf(id.toString()));id} }
 fun remove(id:Long){writableDatabase.delete("fiches","id=?",arrayOf(id.toString()))}
}
class SignPad(ctx:Context):View(ctx) {
 private val stroke=Path()
 private val pen=Paint(3).apply{color=Color.rgb(30,65,130);strokeWidth=5f;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
 private var previous:Bitmap?=null
 private var touched=false
 override fun onDraw(c:Canvas){c.drawColor(Color.WHITE);previous?.let{c.drawBitmap(it,null,Rect(0,0,width,height),null)};c.drawPath(stroke,pen)}
 override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->{stroke.moveTo(e.x,e.y);touched=true};MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP->stroke.lineTo(e.x,e.y)};invalidate();return true}
 fun clear(){stroke.reset();previous=null;touched=false;invalidate()}
 fun load(encoded:String?){clear();if(encoded.isNullOrBlank())return;try{val b=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);previous=BitmapFactory.decodeByteArray(b,0,b.size)}catch(_:Exception){};invalidate()}
 fun encode():String?{if(!touched&&previous==null)return null;val b=Bitmap.createBitmap(width.coerceAtLeast(1),height.coerceAtLeast(1),Bitmap.Config.ARGB_8888);draw(Canvas(b));val s=ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,s);return android.util.Base64.encodeToString(s.toByteArray(),android.util.Base64.NO_WRAP)}
}
class MainActivity:Activity() {
 private lateinit var db:LocalDb
 private var id:Long?=null
 private val inputs=linkedMapOf<String,EditText>()
 private val methods=linkedMapOf<String,Spinner>()
 private val rests=linkedMapOf<String,CheckBox>()
 private var sign:SignPad?=null
 private var pdfPending:ByteArray?=null
 private val days=listOf("Lundi","Mardi","Mercredi","Jeudi","Vendredi","Samedi","Dimanche")
 private val labels=linkedMapOf("formateur" to "Nom du formateur","client" to "Client / organisme","semaine" to "Semaine / période","lieu" to "Lieu de formation","repas" to "Repas (€)","hebergement" to "Hébergement (€)","transport" to "Transport (€)","autres" to "Autres frais (€)","prime_rh" to "Prime RH (€)","commentaires" to "Informations complémentaires")
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);db=LocalDb(this);home()}
 private fun page(title:String):LinearLayout{val root=LinearLayout(this).apply{orientation=1;setBackgroundColor(Color.rgb(247,249,252))};setContentView(root);root.addView(TextView(this).apply{text=title;textSize=24f;setTextColor(Color.rgb(23,50,104));setPadding(24,30,16,20)});val sc=ScrollView(this);root.addView(sc,LinearLayout.LayoutParams(-1,0,1f));return LinearLayout(this).apply{orientation=1;setPadding(20,8,20,30);sc.addView(this)}}
 private fun button(parent:LinearLayout,title:String,action:()->Unit){parent.addView(Button(this).apply{text=title;isAllCaps=false;setOnClickListener{action()}})}
 private fun section(parent:LinearLayout,title:String){parent.addView(TextView(this).apply{text=title;textSize=19f;setTextColor(Color.rgb(28,68,140));setPadding(0,22,0,8)})}
 private fun field(parent:LinearLayout,key:String,label:String,initial:String="",numeric:Boolean=false):EditText {val e=EditText(this).apply{hint=label;setText(initial);textSize=16f;if(numeric)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED};inputs[key]=e;parent.addView(e,LinearLayout.LayoutParams(-1,-2));return e}
 private fun home(){id=null;val body=page("Suivi Formateur");button(body,"+ Nouvelle fiche"){edit(null)};section(body,"Fiches enregistrées");val items=db.all();if(items.isEmpty())body.addView(TextView(this).apply{text="Aucune fiche pour le moment"});items.forEach{(itemId,title)->button(body,title){edit(itemId)}};section(body,"Stockage local · Utilisable sans connexion")}
 private fun edit(itemId:Long?){id=itemId;inputs.clear();methods.clear();rests.clear();val loaded=itemId?.let{db.get(it)};val data=loaded?.first?:JSONObject();val body=page(if(itemId==null)"Nouvelle fiche" else "Modifier la fiche");button(body,"← Mes fiches"){home()};section(body,"Informations générales");listOf("formateur","client","semaine","lieu").forEach{field(body,it,labels.getValue(it),data.optString(it))};section(body,"Heures et congés / repos")
  days.forEach{day->val key=day.lowercase(Locale.ROOT);section(body,day);val e=field(body,key,"Heures",data.optString(key),true);val spinner=Spinner(this);spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Heure manuelle","Carte pointage"));spinner.setSelection(if(data.optString(key+"_mode")=="Carte pointage")1 else 0);methods[key]=spinner;body.addView(spinner);val cb=CheckBox(this).apply{text="Congé / Repos";isChecked=data.optBoolean(key+"_repos",false)};rests[key]=cb;body.addView(cb);cb.setOnCheckedChangeListener{_,checked->e.isEnabled=!checked;spinner.isEnabled=!checked};e.isEnabled=!cb.isChecked;spinner.isEnabled=!cb.isChecked}
  section(body,"Frais professionnels");listOf("repas","hebergement","transport","autres").forEach{field(body,it,labels.getValue(it),data.optString(it),true)};section(body,"Prime RH");field(body,"prime_rh","Prime RH (€)",data.optString("prime_rh"),true);section(body,"Informations complémentaires");field(body,"commentaires","Commentaires",data.optString("commentaires"));section(body,"Signature");sign=SignPad(this);body.addView(sign,LinearLayout.LayoutParams(-1,220));sign?.load(loaded?.second);button(body,"Effacer la signature"){sign?.clear()};button(body,"Enregistrer"){save();Toast.makeText(this,"Fiche enregistrée",Toast.LENGTH_SHORT).show()};button(body,"Exporter en PDF"){save();exportPdf()};button(body,"Envoyer PDF par e-mail (GMX)"){save();emailPdf()};if(itemId!=null)button(body,"Supprimer la fiche"){AlertDialog.Builder(this).setMessage("Supprimer cette fiche ?").setNegativeButton("Annuler",null).setPositiveButton("Supprimer"){_,_->db.remove(itemId);home()}.show()}
 }
 private fun data():JSONObject{val j=JSONObject();inputs.forEach{(k,e)->j.put(k,e.text.toString())};methods.forEach{(k,s)->j.put(k+"_mode",s.selectedItem.toString())};rests.forEach{(k,c)->j.put(k+"_repos",c.isChecked)};return j}
 private fun save(){val j=data();id=db.put(id,j.optString("formateur").ifBlank{"Sans nom"}+" · "+j.optString("semaine").ifBlank{"Sans période"},j,sign?.encode())}
 private fun number(v:String):Double=v.trim().replace(',','.').toDoubleOrNull()?:0.0
 private fun pdf():ByteArray{val j=data();val doc=PdfDocument();val p=doc.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val c=p.canvas;val pen=Paint(3).apply{color=Color.rgb(23,50,104);textSize=19f;typeface=Typeface.DEFAULT_BOLD};c.drawText("SUIVI FORMATEUR",32f,45f,pen);pen.color=Color.DKGRAY;pen.textSize=11f;pen.typeface=Typeface.DEFAULT;var y=76f
  fun line(k:String,v:String){if(y>665f)return;c.drawText(k.take(30),32f,y,pen);c.drawText(v.replace('\n',' ').take(45),235f,y,pen);y+=23f}
  listOf("formateur","client","semaine","lieu").forEach{line(labels.getValue(it),j.optString(it))}
  days.forEach{day->val k=day.lowercase(Locale.ROOT);val rest=j.optBoolean(k+"_repos");line(day,if(rest)"Congé / Repos" else j.optString(k).ifBlank{"0"}+" h — "+j.optString(k+"_mode","Heure manuelle"))}
  listOf("repas","hebergement","transport","autres","prime_rh","commentaires").forEach{line(labels.getValue(it),j.optString(it))}
  val hours=days.sumOf{val k=it.lowercase(Locale.ROOT);if(j.optBoolean(k+"_repos"))0.0 else number(j.optString(k))}
  val fees=listOf("repas","hebergement","transport","autres").sumOf{number(j.optString(it))}
  pen.typeface=Typeface.DEFAULT_BOLD;pen.textSize=12f;c.drawText("Heures : %.2f h".format(Locale.FRANCE,hours),32f,710f,pen);c.drawText("Frais : %.2f €".format(Locale.FRANCE,fees),200f,710f,pen);c.drawText("Prime RH : %.2f €".format(Locale.FRANCE,number(j.optString("prime_rh"))),365f,710f,pen);c.drawText("Signature :",32f,750f,pen)
  sign?.encode()?.let{try{val bytes=android.util.Base64.decode(it,android.util.Base64.DEFAULT);val b=BitmapFactory.decodeByteArray(bytes,0,bytes.size);if(b!=null)c.drawBitmap(b,null,Rect(125,736,325,818),null)}catch(_:Exception){}}
  doc.finishPage(p);val stream=ByteArrayOutputStream();doc.writeTo(stream);doc.close();return stream.toByteArray()
 }
 private fun exportPdf(){pdfPending=pdf();val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/pdf";putExtra(Intent.EXTRA_TITLE,"suivi_formateur.pdf")};startActivityForResult(intent,123)}
 private fun emailPdf(){try{val f=java.io.File(cacheDir,"shared_pdfs").apply{mkdirs()}.resolve("suivi_formateur.pdf");f.writeBytes(pdf());val uri=FileProvider.getUriForFile(this,packageName+".fileprovider",f);val intent=Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);putExtra(Intent.EXTRA_SUBJECT,"Fiche Suivi Formateur");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=ClipData.newUri(contentResolver,"PDF",uri)};startActivity(Intent.createChooser(intent,"Envoyer avec GMX ou une autre application"))}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}}
 override fun onActivityResult(requestCode:Int,resultCode:Int,result:Intent?){super.onActivityResult(requestCode,resultCode,result);if(requestCode==123&&resultCode==RESULT_OK){try{val uri:Uri=result?.data?:return;contentResolver.openOutputStream(uri)?.use{it.write(pdfPending?:return)};Toast.makeText(this,"PDF enregistré",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}finally{pdfPending=null}}}
}
