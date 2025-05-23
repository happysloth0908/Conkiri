package com.conkiri.domain.base.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conkiri.domain.base.dto.response.UserResponseDTO;
import com.conkiri.domain.user.entity.User;
import com.conkiri.domain.user.service.UserReadService;
import com.conkiri.global.auth.token.UserPrincipal;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/main")
@RequiredArgsConstructor
public class MainPageController {

	private final UserReadService userReadService;

	@GetMapping("/user-info")
	public UserResponseDTO getUserInformation(@AuthenticationPrincipal UserPrincipal userPrincipal) {

		User user = userReadService.findUserByIdOrElseThrow(userPrincipal.getUserId());
		return UserResponseDTO.from(user);
	}
}
