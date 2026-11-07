package com.medconnect.video_provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class VideoProviderApplicationTests {

	@Autowired
	WebApplicationContext ctx;

	JsonNode create(MockMvc mvc, UUID cid) throws Exception {
		var r = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"consultationId\":\"" + cid + "\"}")).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		return JsonMapper.builder().build().readTree(r.getContentAsString());
	}

	@Test
	void createSessionIsIdempotentPerConsultationAndEndReturns204() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		UUID cid = UUID.randomUUID();
		JsonNode first = create(mvc, cid);
		String sid = first.get("sessionId").asString();
		assertThat(first.get("joinUrls").get("patient").asString()).startsWith("http://localhost:8090/room/" + sid + "?t=");
		assertThat(first.get("joinUrls").get("doctor").asString()).isNotEqualTo(first.get("joinUrls").get("patient").asString());
		assertThat(create(mvc, cid)).isEqualTo(first);
		assertThat(create(mvc, UUID.randomUUID()).get("sessionId").asString()).isNotEqualTo(sid);

		assertThat(mvc.perform(post("/sessions/" + sid + "/end")).andReturn().getResponse().getStatus()).isEqualTo(204);
		assertThat(mvc.perform(post("/sessions/unknown/end")).andReturn().getResponse().getStatus()).isEqualTo(404);
		assertThat(mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andReturn().getResponse().getStatus()).isEqualTo(400);
	}
}
