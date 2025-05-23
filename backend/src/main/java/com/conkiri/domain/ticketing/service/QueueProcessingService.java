package com.conkiri.domain.ticketing.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.time.ZoneId;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.conkiri.domain.ticketing.dto.ServerMetricsDTO;
import com.conkiri.domain.ticketing.dto.response.WaitingTimeResponseDTO;
import com.conkiri.domain.user.entity.User;
import com.conkiri.domain.user.service.UserReadService;
import com.conkiri.global.exception.ticketing.DuplicateTicketingException;
import com.conkiri.global.exception.ticketing.NotStartedTicketingException;
import com.conkiri.global.util.RedisKeys;
import com.conkiri.global.util.WebSocketConstants;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueProcessingService {

	private final RedisTemplate<String, String> redisTemplate;
	private final SimpMessagingTemplate messagingTemplate;
	private final ServerMonitorService serverMonitorService;
	private final UserReadService userReadService;

	// 티켓팅 시작 및 종료 시간을 Redis 에 설정합니다
	public void setTicketingTime(LocalDateTime startTime, LocalDateTime endTime) {
		log.info("Setting method ");
		redisTemplate.opsForHash().put(RedisKeys.TIME, "startTime", startTime.toString());
		redisTemplate.opsForHash().put(RedisKeys.TIME, "endTime", endTime.toString());

		String dummyId = "dummy_user";
		double dummyScore = Double.MIN_VALUE;
		redisTemplate.opsForZSet().add(RedisKeys.QUEUE, dummyId, dummyScore);
	}

	// 현재 시간이 티켓팅 가능 시간 범위 내인지 확인합니다.
	public boolean isTicketingActive() {

		String startTimeStr = (String)redisTemplate.opsForHash().get(RedisKeys.TIME, "startTime");
		String endTimeStr = (String)redisTemplate.opsForHash().get(RedisKeys.TIME, "endTime");
		if (startTimeStr == null || endTimeStr == null)
			return false;

		LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
		LocalDateTime startTime = LocalDateTime.parse(startTimeStr);
		LocalDateTime endTime = LocalDateTime.parse(endTimeStr);
		return now.isAfter(startTime) && now.isBefore(endTime);
	}

	// 사용자가 티켓팅에 참여 가능한지 확인합니다.
	public boolean canJoinTicketing(Long userId) {

		String userHistoryKey = RedisKeys.getUserHistoryKey(userId);

		// history가 없으면 당연히 참여 가능
		if (!redisTemplate.hasKey(userHistoryKey)) {
			return true;
		}

		// history가 있더라도, 예매 내역이 없으면(reserveTime이 없으면) 참여 가능
		String reserveTime = (String)redisTemplate.opsForHash().get(userHistoryKey, "reserveTime");
		return reserveTime == null;
	}

	// 사용자를 대기열에 추가
	public void addToQueue(Long userId) {

		validateQueueRequest(userId);

		double score = System.nanoTime();
		String historyKey = RedisKeys.getUserHistoryKey(userId);
		addUserToQueue(userId, score);

		Long zeroBasedRank = redisTemplate.opsForZSet().rank(RedisKeys.QUEUE, String.valueOf(userId));
		saveUserQueueHistory(zeroBasedRank, score, historyKey);

		WaitingTimeResponseDTO waitingTimeResponseDTO = getEstimatedWaitingTime(userId);
		notifyWaitingTime(userId, waitingTimeResponseDTO);
	}

	// 사용자를 Redis Sorted Set 대기열에 추가합니다.
	private void addUserToQueue(Long userId, double score) {

		String userIdStr = String.valueOf(userId);
		redisTemplate.opsForZSet().add(RedisKeys.QUEUE, userIdStr, score);
	}

	// 사용자의 대기열 정보를 Redis 에 저장합니다
	private void saveUserQueueHistory(Long position, double score, String historyKey) {

		redisTemplate.opsForHash().put(historyKey, "queueTime", String.valueOf(score));
		redisTemplate.opsForHash().put(historyKey, "position", String.valueOf(position));
		log.info("Saved user history - position: {}, score: {}", position, score);
	}

	private void notifyWaitingTime(Long userId, WaitingTimeResponseDTO waitingTime) {
		log.info("Sending waiting time to user {}, {}, {}, {}", userId, waitingTime.getEstimatedWaitingSeconds(),
			waitingTime.getUsersAfter(), waitingTime.getPosition());  // 로그 추가
		User user = userReadService.findUserByIdOrElseThrow(userId);
		messagingTemplate.convertAndSendToUser(
			user.getEmail(),
			WebSocketConstants.WAITING_TIME_DESTINATION,
			waitingTime
		);
	}

	// 사용자의 현재 대기열 위치를 조회합니다.
	public Long getQueuePosition(Long userId) {
		return redisTemplate.opsForZSet().rank(RedisKeys.QUEUE, String.valueOf(userId));
	}

	// 서버 부하에 따라 대기열을 주기적으로 처리합니다.
	@Scheduled(fixedRate = 5000)  // 5초마다 체크
	public void processWaitingQueue() {

		if (!isTicketingActive()) {
			return;
		}
		ServerMetricsDTO serverLoad = serverMonitorService.getCurrentServerLoad();
		int batchSize = serverMonitorService.calculateBatchSize(serverLoad);
		processNextBatch(batchSize);
	}

	// 지정된 배치 크기만큼 대기열에서 사용자를 처리합니다.
	private void processNextBatch(int batchSize) {

		if (isQueueEmpty()) {
			return;
		}
		Set<String> nextBatch = fetchNextBatch(batchSize);
		if (!nextBatch.isEmpty()) {
			processUsersEntrance(nextBatch);
			updateWaitingTimes();
		}
	}

	// 대기열이 비어있는지 확인합니다.
	private boolean isQueueEmpty() {
		return redisTemplate.opsForZSet().size(RedisKeys.QUEUE) == 0;
	}

	// 다음 처리할 배치의 사용자들을 조회합니다.
	private Set<String> fetchNextBatch(int batchSize) {
		return redisTemplate.opsForZSet()
			.range(RedisKeys.QUEUE, 1, batchSize);
	}

	// 배치의 사용자들을 입장 처리합니다
	private void processUsersEntrance(Set<String> userIds) {

		userIds.forEach(userId -> {
			if (!userId.equals("dummy_user")) {  // 더미 유저 체크 필요
				User user = userReadService.findUserByIdOrElseThrow(Long.parseLong(userId));
				log.info("Sending entrance notification to user: {}", userId);  // 로그 추가
				messagingTemplate.convertAndSendToUser(
					user.getEmail(),
					WebSocketConstants.NOTIFICATION_DESTINATION,
					true
				);
			}
		});
		removeProcessedUsersFromQueue(userIds.size());
	}

	// 처리 완료된 사용자들을 대기열에서 제거합니다, batchSize = 제거할 배치 크기
	private void removeProcessedUsersFromQueue(int batchSize) {
		redisTemplate.opsForZSet().removeRange(RedisKeys.QUEUE, 1, batchSize);
	}

	// 모든 대기 중인 사용자들의 대기 시간을 업데이트합니다.
	private void updateWaitingTimes() {

		Set<String> waitingUsers = getAllWaitingUsers();
		waitingUsers.forEach(this::updateUserWaitingTime);
	}

	// 현재 대기 중인 모든 사용자를 조회합니다.
	private Set<String> getAllWaitingUsers() {
		return redisTemplate.opsForZSet().range(RedisKeys.QUEUE, 1, -1);
	}

	// 개별 사용자의 대기 시간을 업데이트합니다.
	private void updateUserWaitingTime(String userIdStr) {

		Long userId = Long.parseLong(userIdStr);
		WaitingTimeResponseDTO waitingTime = getEstimatedWaitingTime(userId);

		if (waitingTime != null) {
			notifyWaitingTime(userId, waitingTime);
		}
	}

	public WaitingTimeResponseDTO getEstimatedWaitingTime(Long userId) {
		Long position = getQueuePosition(userId);
		if (position == null) {
			return WaitingTimeResponseDTO.of(0L, 0L, 0L, 0L);
		}

		ServerMetricsDTO serverLoad = serverMonitorService.getCurrentServerLoad();
		int batchSize = serverMonitorService.calculateBatchSize(serverLoad);

		// 대기번호는 1부터 시작
		Long waitingNumber = position;
		Long usersAhead = position - 1;

		// 대기 시간 계산 (5초 주기, 배치 크기 고려)
		// 자신의 순서가 처리될 배치 번호 계산 (올림)
		Long myBatchNumber = (usersAhead + batchSize - 1) / batchSize;
		// 배치 번호 * 5초
		Long estimatedSeconds = myBatchNumber * 5L;

		Long totalWaiting = redisTemplate.opsForZSet().size(RedisKeys.QUEUE) - 1;
		Long usersAfter = Math.max(0L, totalWaiting - waitingNumber);

		return WaitingTimeResponseDTO.of(waitingNumber, usersAhead, estimatedSeconds, usersAfter);
	}

	// 대기열 참여 요청의 유효성을 검증합니다.
	public void validateQueueRequest(Long userId) {

		if (!canJoinTicketing(userId)) {
			throw new DuplicateTicketingException();
		}
		if (!isTicketingActive()) {
			throw new NotStartedTicketingException();
		}
	}
}
