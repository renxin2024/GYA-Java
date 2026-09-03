package cn.renxinblog.c03;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.List;
/** C03：Runtime 注入虚构订单事实；只记录候选请求，绝不执行订单动作。 */
public final class Main {
 static final ObjectMapper JSON=new ObjectMapper();
 record S(String n,String c,String t,String id){}
 static final List<S> CASES=List.of(new S("paid-refund","订单 O-100：已支付，尚未发货。","refund_order","O-100"),new S("unpaid-cancel","订单 O-200：未支付，尚未发货。","cancel_order","O-200"),new S("missing-order","未提供订单号；Runtime 无可验证订单状态。",null,null),new S("no-intent","没有退款或取消意图。",null,null));
 public static void main(String[] a)throws Exception{boolean swapped=List.of(a).contains("--swapped");for(S s:CASES)System.out.printf("native-tools/%s: expected=%s fixture=%s%n",s.n,s.t,s.c);System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tools(swapped)));System.out.println("只构造契约与 Trace，不执行退款、取消或查询订单。");}
 static ArrayNode tools(boolean swapped){ArrayNode a=JSON.createArrayNode();a.add(tool("refund_order","仅当 Runtime 已验证订单已支付时，提交退款请求。"));a.add(tool("cancel_order","仅当 Runtime 已验证订单未支付且未发货时，提交取消请求。"));if(swapped){ObjectNode x=(ObjectNode)a.get(0).path("function"),y=(ObjectNode)a.get(1).path("function");String d=x.path("description").asText();x.put("description",y.path("description").asText());y.put("description",d);}return a;}
 static ObjectNode tool(String n,String d){ObjectNode x=JSON.createObjectNode();x.put("type","function");ObjectNode f=x.putObject("function");f.put("name",n);f.put("description",d);ObjectNode p=f.putObject("parameters");p.put("type","object");p.putObject("properties").putObject("order_id").put("type","string");p.putArray("required").add("order_id");return x;}
}
