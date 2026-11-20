package ru.magazinryadom
import android.content.Context
import org.json.*
import java.net.*
import kotlin.math.*
object StoreRepository {
 private val endpoints=listOf("https://overpass-api.de/api/interpreter","https://overpass.kumi.systems/api/interpreter","https://overpass.nchc.org.tw/api/interpreter")
 fun search(lat:Double,lon:Double,term:String,ctx:Context):List<Store>{
  val q=term.trim(); require(q.isNotEmpty()){"Введи название магазина или категорию."}; val around="around:6000,$lat,$lon"; val safe=q.replace("\\","\\\\").replace("\"","\\\"")
  val selector=when(q.lowercase()){
   "магазин","магазины","все магазины"->"""nwr["shop"]($around);"""
   "продукты","продуктовые","супермаркеты","еда"->"""nwr["shop"~"supermarket|convenience|greengrocer|grocery"]($around);"""
   "аптека","аптеки"->"""nwr["amenity"="pharmacy"]($around);"""
   "одежда"->"""nwr["shop"="clothes"]($around);"""
   "обувь"->"""nwr["shop"="shoes"]($around);"""
   "электроника","техника"->"""nwr["shop"~"electronics|computer|mobile_phone"]($around);"""
   "зоотовары","зоомагазины"->"""nwr["shop"="pet"]($around);"""
   "косметика"->"""nwr["shop"~"cosmetics|beauty"]($around);"""
   else->"""nwr["shop"]["name"~"$safe",i]($around); nwr["shop"]["brand"~"$safe",i]($around); nwr["shop"]["operator"~"$safe",i]($around); nwr["name"~"$safe",i]($around);"""
  }
  val query="[out:json][timeout:25];($selector);out center tags;"; var error:Exception?=null
  for(ep in endpoints) try {
   val c=URL(ep).openConnection() as HttpURLConnection; c.requestMethod="POST"; c.doOutput=true; c.connectTimeout=12000;c.readTimeout=30000;c.setRequestProperty("User-Agent","MagazinRyadom/1.2 Android");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
   c.outputStream.use{it.write(("data="+URLEncoder.encode(query,"UTF-8")).toByteArray())}; val code=c.responseCode; val stream=if(code in 200..299)c.inputStream else c.errorStream;val body=stream?.bufferedReader()?.use{it.readText()}.orEmpty();c.disconnect();if(code !in 200..299)throw Exception("Сервер поиска временно недоступен (HTTP $code).")
   val a=JSONObject(body).optJSONArray("elements")?:JSONArray();val out=mutableListOf<Store>();for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;val t=o.optJSONObject("tags")?:JSONObject();val center=o.optJSONObject("center");val la=if(o.has("lat"))o.optDouble("lat") else center?.optDouble("lat")?:continue;val lo=if(o.has("lon"))o.optDouble("lon") else center?.optDouble("lon")?:continue
    val name=t.optString("name").ifBlank{t.optString("brand").ifBlank{t.optString("shop").ifBlank{"Магазин"}}};val addr=listOf(t.optString("addr:street"),t.optString("addr:housenumber"),t.optString("addr:city")).filter{it.isNotBlank()}.joinToString(", ").ifBlank{"Адрес не указан в OpenStreetMap"};out.add(Store(name,addr,la,lo,distance(lat,lon,la,lo)))}
   return (out+custom(ctx,lat,lon).filter{q in listOf("магазин","магазины","все магазины")||it.name.contains(q,true)||it.address.contains(q,true)}).distinctBy{"${it.lat},${it.lon}"}.sortedBy{it.distance}.take(50)
  }catch(e:Exception){error=e}
  val saved=custom(ctx,lat,lon).filter{it.name.contains(q,true)||it.address.contains(q,true)||q=="магазины"};if(saved.isNotEmpty())return saved
  throw Exception(error?.message?:"Серверы OpenStreetMap временно не отвечают. Повтори поиск позже.")
 }
 fun geocode(address:String):Pair<Double,Double>{val u="https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q="+URLEncoder.encode(address,"UTF-8");val c=URL(u).openConnection() as HttpURLConnection;c.connectTimeout=10000;c.readTimeout=20000;c.setRequestProperty("User-Agent","MagazinRyadom/1.2 Android");try{if(c.responseCode !in 200..299)throw Exception("Сервис адресов временно недоступен.");val a=JSONArray(c.inputStream.bufferedReader().use{it.readText()});if(a.length()==0)throw Exception("Адрес не найден. Укажи улицу, дом и город.");val o=a.getJSONObject(0);return o.getString("lat").toDouble() to o.getString("lon").toDouble()}finally{c.disconnect()}}
 fun save(ctx:Context,n:String,a:String,la:Double,lo:Double){val p=ctx.getSharedPreferences("custom",0);val ar=try{JSONArray(p.getString("items","[]"))}catch(e:Exception){JSONArray()};ar.put(JSONObject().put("n",n).put("a",a).put("lat",la).put("lon",lo));p.edit().putString("items",ar.toString()).apply()}
 private fun custom(ctx:Context,la:Double,lo:Double):List<Store>{val ar=try{JSONArray(ctx.getSharedPreferences("custom",0).getString("items","[]"))}catch(e:Exception){JSONArray()};return (0 until ar.length()).mapNotNull{i->try{val o=ar.getJSONObject(i);Store(o.getString("n"),o.getString("a"),o.getDouble("lat"),o.getDouble("lon"),distance(la,lo,o.getDouble("lat"),o.getDouble("lon")))}catch(e:Exception){null}}}
 fun distance(a:Double,b:Double,c:Double,d:Double):Double{val p=Math.toRadians(c-a);val q=Math.toRadians(d-b);val x=sin(p/2).pow(2)+cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(q/2).pow(2);return 12742000.0*asin(sqrt(x))}
}
