package com.whu.post.api.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.whu.common.entity.Application;
import com.whu.common.entity.Notification;
import com.whu.common.entity.Post;
import com.whu.common.entity.UserVisitAction;
import com.whu.common.exception.GlobalException;
import com.whu.common.result.CodeMsg;
import com.whu.common.util.UUIDUtil;
import com.whu.common.vo.PostVO;
import com.whu.post.api.ApplicationApi;
import com.whu.post.api.NotificationApi;
import com.whu.post.api.PostApi;
import com.whu.post.dao.ApplicationDao;
import com.whu.post.dao.PostDao;
import com.whu.post.dao.UserVisitActionDao;
import com.whu.post.rabbitmq.MQSender;
import javafx.geometry.Pos;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@org.springframework.stereotype.Service
public class ApplicationApiImpl implements ApplicationApi {

    @Autowired
    private ApplicationDao applicationDao;

    @Autowired
    private PostDao postDao;

    @Autowired
    private NotificationApi notificationApi;

    @Autowired
    private PostApi postApi;

    @Autowired
    private MQSender mqSender;

    /**
     * 创建Application
     *
     * @param application
     */
    @Override
    public Map<String, Object> create(Application application) {
        Application app = applicationDao.getByPostIdAndApplicant(application.getPostId(), application.getApplicant());
        if (app != null){
            throw new GlobalException(CodeMsg.APPLICATION_DUPLICATE);
        }

        // 1.存入数据库
        application.setApplicationId(UUIDUtil.uuid());
        application.setCreateTime(new Timestamp(System.currentTimeMillis()));
        applicationDao.insert(application);

        // 2.记录日志
        UserVisitAction userVisitAction = new UserVisitAction();
        userVisitAction.setActionId(UUIDUtil.uuid());
        userVisitAction.setApplyPostId(application.getPostId());
        PostVO postVO = postApi.getVOById(application.getPostId(), null);
        userVisitAction.setSnowflakeId(postVO.getSnowflakeId());
        userVisitAction.setUserId(application.getApplicant());
        userVisitAction.setCreateTime(new Timestamp(System.currentTimeMillis()));
        mqSender.sendUserVisitActionMsg(userVisitAction);

        // 3.发送消息
        Post post = postDao.getById(application.getPostId());

        Map<String, Object> map = new HashMap<>();
        map.put("application", application);
        if (post != null){
            Notification notification = new Notification();
            notification.setNotificationId(UUIDUtil.uuid());
            notification.setApplicationId(application.getApplicationId());
            notification.setPostId(application.getPostId());
            notification.setReceiver(post.getPoster());
            notification.setType(0);
            notification.setContent("有人向您的帖子发送了申请");
            notification.setCreateTime(new Timestamp(System.currentTimeMillis()));
            notificationApi.sendNotification(notification);
            map.put("notification", notification);
        }

        return map;
    }

    /**
     * 处理application
     *
     * @param applicationId
     * @param status
     */
    @Override
    public Notification handleApplication(String applicationId, String postId, String applicant, Integer status) {
        // 1.插入数据库
        applicationDao.changeStatus(applicationId, status, new Date());
        String message = null;
        // 2.未通过 人数已满
        if (status == 3){
            message = "您的申请未通过, 人数已满";
        }
        // 3.未通过
        else if (status == 2){
            message = "您的申请未通过";
        }
        // 4.已通过
        else if (status == 1){
            message = "您的申请已通过";
        }

        Notification notification = new Notification();
        notification.setNotificationId(UUIDUtil.uuid());
        notification.setApplicationId(applicationId);
        notification.setPostId(postId);
        notification.setReceiver(applicant);
        notification.setType(1);
        notification.setContent(message);
        notification.setCreateTime(new Timestamp(System.currentTimeMillis()));
        notificationApi.sendNotification(notification);

        return notification;
    }

    /**
     * 列出post的所有的申请
     *
     * @param postId
     * @return
     */
    @Override
    public PageInfo<Application> listPageByPostId(String postId, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Application> applications = applicationDao.listByPostId(postId);
        PageInfo<Application> pageInfo = new PageInfo<>(applications);
        pageInfo.setPageNum(pageNum);
        pageInfo.setPageSize(pageSize);
        return pageInfo;
    }

    /**
     * 列出某人所有的申请
     *
     * @param applicant
     * @param status -1-所有 0-未审核 1-已通过 2-未通过
     * @param pageNum
     * @param pageSize
     * @return
     */
    @Override
    public PageInfo<Application> listPageByUserId(String applicant, Integer status, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Application> applications;
        if (status == -1){
            applications = applicationDao.listByUserId(applicant);
        }
        else {
            applications = applicationDao.listByUserIdAndStatus(applicant, status);
        }
        PageInfo<Application> pageInfo = new PageInfo<>(applications);
        pageInfo.setPageSize(pageSize);
        pageInfo.setPageNum(pageNum);
        return pageInfo;
    }

    /**
     * 通过id获取application
     *
     * @param applicationId
     * @return
     */
    @Override
    public Application getById(String applicationId) {
        return applicationDao.getById(applicationId);
    }

    /**
     * 删除申请
     *
     * @param applicationId
     */
    @Override
    public void deleteById(String applicationId) {
        applicationDao.deleteById(applicationId);
    }

    /**
     * 清空用户所有申请
     *
     * @param applicant
     */
    @Override
    public void clear(String applicant, Integer status) {
        if (status == -1){
            applicationDao.deleteByApplicant(applicant);
        }
        else {
            applicationDao.deleteByApplicantAndStatus(applicant, status);
        }

    }
}
