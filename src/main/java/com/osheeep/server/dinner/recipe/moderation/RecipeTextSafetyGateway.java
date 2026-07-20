package com.osheeep.server.dinner.recipe.moderation;

public interface RecipeTextSafetyGateway {

    RecipeTextSafetyResult check(String openid, String title, String content);
}
