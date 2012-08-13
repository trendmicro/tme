<%@ page trimDirectiveWhitespaces="true"%>
<%@ page contentType="text/html; charset=UTF-8"%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c"%>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01//EN" "http://www.w3.org/TR/html4/strict.dtd">
<c:set var="prefix"><%= getServletConfig().getInitParameter("pathprefix") %></c:set>
<html>
<head>
<meta http-equiv="Content-type" content="text/html;charset=UTF-8">
<title>Graph: ${it.name}</title>
<link rel="stylesheet" type="text/css" href="${prefix}/static/canviz/canviz.css" />
<link rel="stylesheet" type="text/css" href="${prefix}/static/jquery/jquery-ui/css/ui-lightness/jquery-ui-1.8.16.custom.css" />
<link rel="stylesheet" type="text/css" href="${prefix}/static/graph-editor.css" />

<script type="text/javascript" src="${prefix}/static/jquery/jquery.js"></script>
<script type="text/javascript">
  jQuery.noConflict();
</script>
<script type="text/javascript" src="${prefix}/static/jquery/jquery.cookie.js"></script>
<script type="text/javascript" src="${prefix}/static/jquery/jquery-ui/js/jquery-ui-1.8.16.custom.min.js"></script>
<script type="text/javascript" src="${prefix}/static/jquery/jquery-ui/js/jquery.mouse.ui.js"></script>
<script type="text/javascript" src="${prefix}/static/canviz/prototype/prototype.js"></script>
<script type="text/javascript" src="${prefix}/static/canviz/canviz.js"></script>
<script type="text/javascript" src="${prefix}/static/canviz/path/path.js"></script>

<script type="text/javascript">
	if(jQuery.cookie('graph_editor_scale') == null){
		jQuery.cookie('graph_editor_scale', '1.0', {path: '/' });
	}
	
	function remove_rule(rule){
		if(confirm('Are you sure to remove rule ' + rule + ' ?')){
		jQuery.ajax({
			type: "DELETE",
			async: false,
			url: "${it.name}/rule/" + rule,
			success: function(){
			},
			error: function(xhr,text,err){
				alert(err);					
			}
		});
		location.reload();
		}
	}
	
	function add_rule(rule){
		jQuery.ajax({
			type: "PUT",
			async: false,
			url: "${it.name}/rule/" + rule,
			success: function(){
			},
			error: function(xhr,text,err){
				alert(err);					
			}
		});
		location.reload();
	}
	
	function processor_onclick(processor){
		jQuery('<div title="Processor Editor">')
		.append('<input type=button value="Remove ' + processor + '" onclick="remove_processor(\'' + processor + '\')">')
		.append('<iframe onload="jQuery(this).contents().find(\'.ui-widget-header\').remove();" id="dialog" >').dialog({
			close: function(){location.reload();},
			modal: true,
			width: window.innerWidth * 0.75, 
			height: window.innerHeight * 0.75
		});
		
		jQuery('#dialog').css("width", "100%");
		jQuery('#dialog').css("height", "80%");
		jQuery('#dialog').attr("src", "/webapp/graph-editor/processor/" + processor);
	}
	
	function remove_processor(processor){
		if(confirm('Are you sure to remove processor ' + processor + ' ?')){
		jQuery.ajax({
			type: "DELETE",
			async: false,
			url: "${it.name}/processor/" + processor,
			success: function(){
			},
			error: function(xhr,text,err){
				alert(err);					
			}
		});
		location.reload();
		}
	}
	
	function add_processor(processor){
		if(processor == null){
			return;
		}
		jQuery.ajax({
			type: "PUT",
			async: false,
			url: "${it.name}/processor/" + processor,
			success: function(){
			},
			error: function(xhr,text,err){
				alert(err);					
			}
		});
		location.reload();
	}
	
	function add_admin(){
		admin=prompt("Please enter admin name: ");
		jQuery.ajax({
			type: "PUT",
			async: false,
			url: "${it.name}/admin/" + admin,
			success: function(){
			},
			error: function(xhr,text,err){
				alert(err);
			}
		});
		location.reload();
	}

	function remove_admin(admin){
		if(confirm('Are you sure to remove admin ' + admin + ' ?')){
		jQuery.ajax({
			type: "DELETE",
			async: false,
			url: "${it.name}/admin/" + admin,
			success: function(){
			},
			error: function(xhr,text,err){
				alert(err);
			}
		});
		location.reload();
		}
	}

	function render_processors(){
		jQuery.ajax({
			type: "GET",
			async: true,
			url: "${prefix}/webapp/graph-editor/processor",
			headers: { 
        		Accept : "application/json",
	        },			
			success: function(data){
				processors = data;
				
				jQuery.ajax({
					type: "GET",
					async: true,
					url: "",
					headers: { 
        				Accept : "application/json",
	        		},
					success: function(data){
						graph = data;
						for(i=0; i < processors.length ; i++){
							//alert(jQuery.inArray(processors[i], graph.processors));
							
							if(!graph.processors){
								jQuery('#processors').append('<option>' + processors[i] + '</option>');
							}
							else if(processors[i]!=graph.processors && jQuery.inArray(processors[i], graph.processors) == -1){
								
								jQuery('#processors').append('<option>' + processors[i] + '</option>');
							}
						}
						//for(i=0; i<data.processors.length; i++){
							//alert(data.processors[i]);
						//}
					},
					error: function(xhr,text,err){
						alert(err);					
					}
				});
			
				/*for(i=0; i < data.length ; i++){
					if(jQuery('#' + data[i]).length == 0){
						jQuery('#processors').append('<option>' + data[i] + '</option>');
					}
				}*/				
  			},
			error: function(xhr,text,err){
				alert(err);					
			}
		});	
	}
	
	function set_graph_scale() {
    	canviz.setScale(parseFloat(jQuery.cookie('graph_editor_scale')));
    	canviz.draw();
    	make_dragNdrop();
	}
	
	function change_scale(inc) {
	//alert(parseFloat(jQuery.cookie('graph_editor_scale')) + inc);
		if(parseFloat(jQuery.cookie('graph_editor_scale')) + inc <= 0.4){
			return;
		}
		jQuery.cookie('graph_editor_scale', parseFloat(jQuery.cookie('graph_editor_scale')) + inc, {path: '/' });
    	
    	set_graph_scale();
	}
	
	function make_dragNdrop(){
		jQuery('a[id^="output-"]').draggable();
   		jQuery('a[id^="output-"]').draggable({revert: true});
   		jQuery('a[id^="output-"]').draggable('enable');
   			 	
   		jQuery('a[id^="input-"]').droppable({
      		drop: function(event, ui) {
      			rule = ui.draggable[0].id.split('-')[1] + '-' + this.id.split('-')[1];
      			add_rule(rule);
      		}
    	});    			
	}
	
	function enable(){
		if(confirm('Are you sure to enable this graph?')){
			jQuery.ajax({
				type: "PUT",
				async: false,
				url: "${prefix}/webapp/graph-editor/graph/${it.name}/enable",
				error: function(xhr,text,err){
					alert(err);					
				}
			});
			location.reload();
		}	
	}
	
	function disable(){
		if(confirm('Are you sure to disable this graph?')){
			jQuery.ajax({
				type: "DELETE",
				async: false,
				url: "${prefix}/webapp/graph-editor/graph/${it.name}/enable",
				error: function(xhr,text,err){
					alert(err);					
				}
			});
			location.reload();
		}
	}
	
	function refresh_metric(exchange){
		idx = exchange.innerHTML.indexOf('<');
		if(idx > 0){
			exname = exchange.innerHTML.substr(0, idx).replace(":","-");
		}
		else {
			exname = exchange.innerHTML.replace(":","-");
		}
        jQuery.ajax({
            type: "GET",
            async: true,
            url: "${prefix}/proxy/<%= getServletConfig().getInitParameter("portalhost") %>/exchanges/" + exname,
                headers: {
                Accept : "application/json",
            },
            success: function(metric){
         	   idx = exchange.innerHTML.indexOf('<');
				if(idx > 0){
					exchange.innerHTML = exchange.innerHTML.substr(0, idx);
				}
                exchange.innerHTML = exchange.innerHTML + '<br>' + metric.Pending + " / " + metric["Max Pending"];
                exchange.innerHTML = exchange.innerHTML + '<br>' + metric["Limit Behavior"];
                setTimeout("refresh_metric(document.getElementById('" + exchange.id + "'));", 5000);                    
            },
            error: function(xhr,text,err){
            	idx = exchange.innerHTML.indexOf('<');
				if(idx > 0){
					exchange.innerHTML = exchange.innerHTML.substr(0, idx);
				}
                //alert(err);
            }
        });
	}
	
	function start_query(){
        jQuery('a[id^="input-"]').each(function(){
        	refresh_metric(this);
        });
    }	
	
	jQuery(document).ready(function() {
		canviz = new Canviz('graph_container');
		
		jQuery.ajax({
			type: "GET",
			async: true,
			url: "",
			headers: { 
        		Accept : "application/x-xdot",
	        },			
			success: function(data){
				//alert(data);
				
				canviz.setScale(parseFloat(jQuery.cookie('graph_editor_scale')));
   			 	canviz.parse(data);
   			 	
   			 	make_dragNdrop();
    			
    			render_processors();
    			
    			if("<%= getServletConfig().getInitParameter("portalhost") %>" != ""){ 
    				start_query();
    			}
  			},
			error: function(xhr,text,err){
				alert(err);					
			}
		});		
		
	});
</script>

</head>
<body class="ui-widget" style="text-align: center">
	<%@ include file="../header.jsp"%>
	<h1>
		Graph: ${it.name}
		<c:choose>
			<c:when test="${it.enabled}">
				<span style="color: green; cursor: pointer;" onclick="disable();">[Enabled]</span>
			</c:when>
			<c:otherwise>
				<span style="color: red; cursor: pointer;" onclick="enable();">[Disabled]</span>
			</c:otherwise>
		</c:choose>

	</h1>
	<div>
		Add Processor: <select id="processors"></select> <input type="button"
			value="add" onclick="add_processor(jQuery('#processors').val());"><br>
		Admin: <c:forEach var="admin" items="${it.admins}"><a href="javascript:remove_admin('${admin}');">${admin}</a> </c:forEach> <input type="button" value="add" onclick="add_admin();"><br>
	</div>

	<hr />
	<div id="graph_container" style="text-align: left">
	<input type="button" style="margin-left: 3px; margin-top: 3px;" class="little_button" value="+" onclick="change_scale(0.2)" />
	<input type="button" style="margin-left: 3px; margin-top: 3px;" class="little_button" value="-" onclick="change_scale(-0.2)" /><br>
	</div>
</body>
</html>
