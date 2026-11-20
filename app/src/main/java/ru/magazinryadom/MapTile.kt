package ru.magazinryadom
import android.graphics.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.*
object MapTile { fun make(la:Double,lo:Double,sla:Double,slo:Double):Bitmap?=try{val z=15;val n=1 shl z;fun x(v:Double)=(v+180)/360*256*n;fun y(v:Double):Double{val r=Math.toRadians(v.coerceIn(-85.0,85.0));return(1-ln(tan(r)+1/cos(r))/Math.PI)/2*256*n};val ux=x(lo);val uy=y(la);val tx=floor(ux/256).toInt();val ty=floor(uy/256).toInt();val c=URL("https://tile.openstreetmap.org/$z/$tx/$ty.png").openConnection() as HttpsURLConnection;c.setRequestProperty("User-Agent","MagazinRyadom/1.2 Android");c.connectTimeout=8000;c.readTimeout=10000;val tile=c.inputStream.use{BitmapFactory.decodeStream(it)};c.disconnect();val b=Bitmap.createBitmap(512,220,Bitmap.Config.ARGB_8888);val cv=Canvas(b);val p=Paint(3);cv.drawColor(Color.LTGRAY);if(tile!=null)cv.drawBitmap(tile,null,Rect(0,0,512,220),p);p.color=Color.BLUE;cv.drawCircle(256f,110f,9f,p);p.color=Color.RED;cv.drawCircle(256+(x(slo)-ux).toFloat(),110+(y(sla)-uy).toFloat(),8f,p);tile?.recycle();b}catch(e:Exception){null} }
